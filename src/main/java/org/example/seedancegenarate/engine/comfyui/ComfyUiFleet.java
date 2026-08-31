package org.example.seedancegenarate.engine.comfyui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 机队的<b>内存快照</b> + <b>待发计数</b>。{@code pick()} 只读这里，零 IO。
 *
 * <h3>为什么快照要不可变、整体替换</h3>
 * 读写比悬殊：{@code pick()} 在提交路径上、一天几万次；探测器 3 秒写一次。
 * 任何加锁方案都是拿高频路径去等低频路径。整体替换 + volatile 让读侧零锁，
 * 而且遍历时不会看见"半个节点已更新、半个还没有"的中间态。
 *
 * <h3>待发计数是这套设计的核心，不是补丁</h3>
 * D-026 原本要求"调度必须每次实时查 /queue"，理由是怕一批提交按同一份过期负载全压到一台。
 * 但实时探测在羊群真正形成的窗口里<b>什么都没做</b>：
 * <pre>
 *   pick() 选中 gpu-1 → POST /upload/image（几十 MB，1~3 秒）→ POST /prompt
 *                                                              ↑ 到这里 gpu-1 的队列才 +1
 * </pre>
 * 这 1~3 秒里再探测 gpu-1，拿到的还是 0 —— 探测是真·实时的，也是真·没用。
 * <p>
 * 待发计数在 {@code pick()} 返回时<b>同步 +1</b>，所以第二次 pick 立刻看得见第一次。
 * 「看得见自己刚发出去的」比「看得见对方队列的真值」更决定性，
 * 而前者探测再实时也拿不到。见 D-026（2026-08-28 修订）。
 *
 * <h3>为什么允许短暂重复计数</h3>
 * prompt 落地后，它同时出现在探测到的 {@code queueDepth} 和还没老化的待发计数里，
 * 重复计约 {@code pendingAgingMs} 那么久。<b>这个重复对所有节点是对称的</b>，
 * 而选节点只看相对大小 —— 不影响选择，只让绝对值偏大。
 * 为消除它要引入"提交成功回执 + 探测起止时刻比对"，复杂度远超收益。
 *
 * <h3>多实例</h3>
 * 待发计数是<b>进程内</b>的：实例 A 发出去的，实例 B 在一个探测周期内看不见。
 * 但快照 3 秒收敛，而任务本身跑 5~20 分钟 —— 3 秒的跨实例偏斜对一个每槽位
 * 占用 300+ 秒的系统是噪声。真不够了把这个类的三个 pending 方法换成 Redis ZSET
 * （和 D-031 的在途登记同一个形状），调用方不用改。
 */
@Slf4j
@Component
public class ComfyUiFleet {

    private final ComfyUiProperties properties;

    /** 整体替换，绝不原地改 */
    private volatile Map<String, NodeState> snapshot;

    /** 写侧串行化。读侧不碰它 —— 快照不可变，读到哪一份都是自洽的 */
    private final Object writeLock = new Object();

    /** nodeId → (派发 token → 派发时刻)。token 让失败提交只释放自己的预约 */
    private final Map<String, ConcurrentHashMap<Long, Long>> pending = new ConcurrentHashMap<>();
    private final AtomicLong dispatchSequence = new AtomicLong();

    public ComfyUiFleet(ComfyUiProperties properties) {
        this.properties = properties;
        this.snapshot = initialSnapshot(properties);
    }

    private static Map<String, NodeState> initialSnapshot(ComfyUiProperties properties) {
        Map<String, NodeState> initial = new LinkedHashMap<>();
        for (ComfyUiProperties.Node node : properties.getNodes()) {
            initial.put(node.getId(), NodeState.initial(node));
        }
        return Map.copyOf(initial);
    }

    /** 当前快照（不可变）。读侧唯一入口 */
    public Map<String, NodeState> snapshot() {
        return snapshot;
    }

    public Collection<NodeState> nodes() {
        return snapshot.values();
    }

    public NodeState node(String id) {
        return id == null ? null : snapshot.get(id);
    }

    /**
     * 按 id 找回节点，供轮询用任务上记录的 {@code node_id} 找回处理它的那台。
     * <p>
     * <b>不过滤 enabled、也不过滤 archived</b> —— 任务还在那台上跑着，
     * 人把它关了、退役了，都还得查得到。查不到的后果是
     * {@code ComfyUiEngine.poll()} 直接返回 {@code failed("找不到处理该任务的 ComfyUI 节点")}，
     * 那是终态不是重试：那台上所有在途任务当场判死，而 GPU 上的 prompt 还会继续跑到完。
     */
    public ComfyUiProperties.Node findNode(String id) {
        NodeState state = node(id);
        if (state == null) {
            return null;
        }
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId(state.id());
        node.setBaseUrl(state.baseUrl());
        node.setEnabled(state.enabled());
        node.setArchived(state.archived());
        node.setWeight(state.weight());
        node.setRemark(state.remark());
        return node;
    }

    /**
     * 快探测（每 3 秒，{@code GET /prompt}）的结果。<b>由本类在锁内做读—改—写</b>。
     * <p>
     * 让调用方「读快照 → 改 → replace」的写法有个必现的丢失更新：
     * 慢探测（每 60 秒）如果落在快探测的读和写之间，它刚合并进去的能力集合
     * 会被快探测那份旧基底覆盖掉，然后要等下一个 60 秒才回来 ——
     * 而这段时间里能力过滤是失效的，且没有任何报错。
     */
    public void applyQueueProbe(List<ComfyUiProperties.Node> configured, List<ProbeResult> results,
                                int unhealthyAfter) {
        Map<String, ProbeResult> byId = new LinkedHashMap<>();
        for (ProbeResult r : results) {
            byId.put(r.nodeId(), r);
        }
        long now = System.currentTimeMillis();
        synchronized (writeLock) {
            Map<String, NodeState> base = snapshot;
            Map<String, NodeState> fresh = new LinkedHashMap<>();
            for (ComfyUiProperties.Node node : configured) {
                // 配置侧以本轮读到的为准，观测侧从上一份继承
                NodeState carried = base.containsKey(node.getId())
                        ? base.get(node.getId()).withConfig(node)
                        : NodeState.initial(node);
                ProbeResult r = byId.get(node.getId());
                if (r == null) {
                    // 这一轮**没探它**（退避中），不是探失败了。原样保留 ——
                    // 当成失败的话，退避中的节点每轮都 +1 失败计数，会永远钉死在最大退避上；
                    // 而且 lastError 会从真正的原因（"502"）被改写成"未探测"，
                    // 运维在页面上看到的就不再是病因而是症状。lastProbedAt 也必须留旧值，
                    // 退避间隔正是拿它算的，刷新它等于每轮都把退避时钟归零。
                    fresh.put(node.getId(), carried);
                    continue;
                }
                if (r.error() != null) {
                    NodeState failed = carried.probeFailed(r.error(), Math.max(unhealthyAfter, 1), now);
                    if (carried.healthy() && !failed.healthy()) {
                        log.warn("ComfyUI 节点摘除: {} 连续失败 {} 次 ({})",
                                failed.id(), failed.consecutiveFailures(), failed.lastError());
                    }
                    fresh.put(node.getId(), failed);
                } else {
                    if (!carried.healthy()) {
                        log.warn("ComfyUI 节点恢复: {} ({})", node.getId(), node.getBaseUrl());
                    }
                    fresh.put(node.getId(), carried.probedOk(r.queueDepth(), now, r.latencyMs()));
                }
            }
            snapshot = Map.copyOf(fresh);
        }
    }

    /** 慢探测（每 60 秒，{@code /object_info} + {@code /system_stats}）成功后合并一台的能力 */
    public void applyCapabilityProbe(String nodeId, Set<String> capabilities, Long vramTotal,
                                     String gpuName, Map<String, String> versions) {
        synchronized (writeLock) {
            NodeState current = snapshot.get(nodeId);
            if (current == null) {
                return; // 节点在这一轮里被移出配置了
            }
            Map<String, NodeState> fresh = new LinkedHashMap<>(snapshot);
            fresh.put(nodeId, current.withCapabilities(capabilities, vramTotal, gpuName, versions));
            snapshot = Map.copyOf(fresh);
        }
    }

    /** 测试用：直接摆一份快照 */
    void replace(Map<String, NodeState> fresh) {
        synchronized (writeLock) {
            this.snapshot = Map.copyOf(fresh);
        }
    }

    /** 一台节点这一轮的快探测结果：{@code error == null} 即成功 */
    public record ProbeResult(String nodeId, Integer queueDepth, long latencyMs, String error) {
        public static ProbeResult ok(String nodeId, int queueDepth, long latencyMs) {
            return new ProbeResult(nodeId, queueDepth, latencyMs, null);
        }

        public static ProbeResult failed(String nodeId, String error) {
            return new ProbeResult(nodeId, null, 0L, error);
        }
    }

    // ---------- 待发计数 ----------

    /** {@code pick()} 选中一台时同步调用。这一步让下一次 pick 立刻看得见这一次 */
    public long markDispatched(String nodeId) {
        long token = dispatchSequence.incrementAndGet();
        pending.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>())
                .put(token, System.currentTimeMillis());
        return token;
    }

    /**
     * 提交失败（上传挂了 / 工作流校验没过 / 节点 500）时归还，不用等老化。
     * <p>
     * 少了这一步，失败的那次派发会让这台节点在整个老化窗口里显得比实际忙 ——
     * 而节点出问题时提交失败往往是连续的，几次下来这台就被彻底晾在一边，
     * 恢复后也要等老化窗口过完才回来。
     */
    public void releaseDispatch(String nodeId, long token) {
        ConcurrentHashMap<Long, Long> reservations = pending.get(nodeId);
        if (reservations != null) {
            reservations.remove(token);
            if (reservations.isEmpty()) {
                pending.remove(nodeId, reservations);
            }
        }
    }

    /**
     * 这台节点上「已派发、对方队列还没反映出来」的数量。读时顺带老化。
     * <p>
     * 老化是唯一的兜底：进程崩溃、submit 线程被杀、任何我们没想到的路径，
     * 留下的计数都会在 {@code pendingAgingMs} 后自己消失。没有它，
     * 泄漏的计数只增不减，最后所有节点都显得很忙而选不出来
     * （D-031 里"漏掉一次减法的计数器只增不减"是同一个坑）。
     */
    public int pendingCount(String nodeId) {
        ConcurrentHashMap<Long, Long> reservations = pending.get(nodeId);
        if (reservations == null) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - properties.getPendingAgingMs();
        reservations.entrySet().removeIf(entry -> entry.getValue() < deadline);
        int count = reservations.size();
        if (count == 0) {
            pending.remove(nodeId, reservations);
        }
        return count;
    }

    /**
     * 有效负载 = (探到的队列深度 + 待发) / 权重。选最小的那台。
     * <p>
     * 除以权重而不是乘：weight=0.45 的 Spark 排 1 个 ≈ H100 排 2.2 个，
     * 于是它稳态队列深度自然落在 H100 的 45% 左右，不需要给每个模型单独配权重
     * （实测 z-image-turbo 2.03x、minimax-h3-t2v-hd 2.34x，差异是均匀的）。
     */
    public double effectiveLoad(NodeState node) {
        return (node.queueDepth() + pendingCount(node.id())) / Math.max(node.weight(), 0.01);
    }
}
