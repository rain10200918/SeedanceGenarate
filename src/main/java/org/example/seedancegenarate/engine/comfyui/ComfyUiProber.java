package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台探测器：把「对方队列现在多深、这台活着没有、它装了哪些 node type」搬进内存快照，
 * 让 {@code pick()} 不用在用户等待的路径上发 HTTP。
 *
 * <h3>三类数据变化速度差三个数量级，所以分两档探</h3>
 * <table border="1">
 *   <tr><th>探什么</th><th>周期</th><th>载荷</th><th>为什么是这个频率</th></tr>
 *   <tr><td>{@code GET /prompt}</td><td>3 秒</td><td>约 37 字节</td><td>队列深度秒级变</td></tr>
 *   <tr><td>{@code /system_stats} + {@code /object_info}</td><td>60 秒</td><td>MB 级</td>
 *       <td>装插件 / 重启才变</td></tr>
 * </table>
 * 队列深度<b>绝不能</b>用 {@code /queue} 取 —— 那个响应里带着队列中每个 prompt 的完整工作流 JSON
 * （可能几 MB），3 秒一轮 × N 台就是持续烧带宽，而 {@code /prompt} 给的是同一个数字。
 *
 * <h3>为什么不加分布式锁</h3>
 * 对账任务（{@code ConcurrencyReconcileTask}）要抢锁，因为它<b>写共享状态</b>，多实例重复执行会打架。
 * 探测器相反 —— 它只往<b>本进程</b>的内存里写，<b>每个实例都必须自己探</b>。
 * 加锁的后果是只有抢到锁的那个实例有新鲜快照，其余实例永远拿着启动时的初值派活。
 *
 * <h3>为什么连 enabled=false 的节点也探</h3>
 * 管理端要能看到一台被人工关掉的机器是死是活；而一台配置里已死很久的节点
 * （比如指向没有进程的端口的那个）一旦被修好，下一轮探测自动回来，不需要任何人操作。
 * 代价是每 3 秒一个 37 字节的请求打到一个 502 上，可以忽略。
 *
 * <h3>一台 hang 住不能拖垮整轮</h3>
 * 全部节点并行探，且在 HTTP 自己的超时之外再加一层 {@code future.get(timeout)}：
 * Hutool 的超时依赖底层连接状态，遇到「TCP 连上了但对端再也不发字节」这类情况未必按时返回，
 * 而串行 + 无外层超时的组合会让整轮探测停摆，快照从此僵死在旧值上 ——
 * 那正是 D-026 最怕的「按同一份过期负载派活」。
 */
@Slf4j
@Component
public class ComfyUiProber {

    private final ComfyUiProperties properties;
    private final ComfyNodeRegistry registry;
    private final ComfyUiClient client;
    private final ComfyUiFleet fleet;
    private final ExecutorService pool;

    /**
     * nodeId → 版本串。<b>必须是并发容器</b>：慢探测把 N 台节点并行提交到线程池，
     * 每个线程都会往这里写、并遍历它算机队分布。第一版用的是 LinkedHashMap，
     * 那是一颗定时炸弹（ConcurrentModificationException / 读到半更新的表）。
     */
    private final Map<String, String> versionFingerprints = new ConcurrentHashMap<>();
    /** 上一次报出去的机队版本分布签名。一样就不再重复说 */
    private final AtomicReference<String> lastDriftSignature = new AtomicReference<>("");
    /** 有没有报过不一致。没报过就不需要报「已统一」 */
    private final AtomicBoolean warnedAboutDrift = new AtomicBoolean(false);

    public ComfyUiProber(ComfyUiProperties properties, ComfyNodeRegistry registry,
                         ComfyUiClient client, ComfyUiFleet fleet) {
        this.properties = properties;
        this.registry = registry;
        this.client = client;
        this.fleet = fleet;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "comfy-probe-" + seq.incrementAndGet());
            t.setDaemon(true); // 探测不该拦着 JVM 退出
            return t;
        });
    }

    // ---------- 快探测：队列深度 + 死活 ----------

    @Scheduled(fixedDelayString = "${video.comfyui.probe-interval-ms:3000}")
    public void probeQueues() {
        // 归档的节点不探（已退役，没必要每 3 秒打一次），但它仍然留在快照里 ——
        // 否则它上面还没跑完的任务会在 poll() 里查不到节点、被直接判 FAILED
        // 快照成员必须用全量清单（含归档）；只有 HTTP 探测目标才过滤归档。
        // 否则 active 列表整体替换快照后，归档节点会消失，它的在途任务随即找不到 node_id。
        List<ComfyUiProperties.Node> configured = registry.nodes();
        List<ComfyUiProperties.Node> probeTargets = active(configured);
        // 熔断：已确诊不健康的节点按指数退避，不再每 3 秒死探。
        // 被跳过的节点**不进 results**，applyQueueProbe 会原样保留它的状态 ——
        // 当成失败的话失败计数每轮都涨，会永远钉死在最大退避上。
        long now = System.currentTimeMillis();
        List<ComfyUiProperties.Node> due = new ArrayList<>(probeTargets.size());
        for (ComfyUiProperties.Node node : probeTargets) {
            NodeState state = fleet.node(node.getId());
            if (state == null || state.dueForProbe(now, properties.getProbeIntervalMs(),
                    properties.getProbeBackoffMaxMs(), properties.getUnhealthyAfterFailures())) {
                due.add(node);
            }
        }

        int timeoutMs = properties.getConnectTimeoutMs();
        List<Future<Integer>> futures = new ArrayList<>(due.size());
        for (ComfyUiProperties.Node node : due) {
            futures.add(pool.submit(() -> client.queueRemaining(node.getBaseUrl(), timeoutMs)));
        }

        long startedAt = System.nanoTime();
        List<ComfyUiFleet.ProbeResult> results = new ArrayList<>(due.size());
        for (int i = 0; i < due.size(); i++) {
            String id = due.get(i).getId();
            try {
                int depth = futures.get(i).get(timeoutMs + 500L, TimeUnit.MILLISECONDS);
                results.add(ComfyUiFleet.ProbeResult.ok(
                        id, depth, (System.nanoTime() - startedAt) / 1_000_000));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                results.add(ComfyUiFleet.ProbeResult.failed(id, rootMessage(e)));
            }
        }
        fleet.applyQueueProbe(configured, results, properties.getUnhealthyAfterFailures());
    }

    // ---------- 慢探测：能力 + 显存 + 版本 ----------

    @Scheduled(fixedDelayString = "${video.comfyui.capability-probe-interval-ms:60000}")
    public void probeCapabilities() {
        for (ComfyUiProperties.Node node : active(registry.nodes())) {
            pool.submit(() -> probeOneCapability(node));
        }
    }

    private void probeOneCapability(ComfyUiProperties.Node node) {
        int timeoutMs = properties.getStatusTimeoutMs();
        try {
            // /object_info 的顶层 key 就是 node type 名字，直接取字段名，不解析每个节点的定义
            JsonNode objectInfo = client.getObjectInfo(node.getBaseUrl(), properties.getReadTimeoutMs());
            Set<String> types = new LinkedHashSet<>();
            for (Iterator<String> it = objectInfo.fieldNames(); it.hasNext(); ) {
                types.add(it.next());
            }
            if (types.isEmpty()) {
                log.warn("ComfyUI 节点 {} 的 /object_info 是空的，本轮不更新能力", node.getId());
                return;
            }

            JsonNode stats = client.getSystemStats(node.getBaseUrl(), timeoutMs);
            JsonNode device = stats.path("devices").path(0);
            JsonNode system = stats.path("system");
            Map<String, String> versions = new LinkedHashMap<>();
            putIfText(versions, "comfyui", system.path("comfyui_version"));
            putIfText(versions, "torch", system.path("pytorch_version"));
            putIfText(versions, "python", system.path("python_version"));

            fleet.applyCapabilityProbe(node.getId(), Set.copyOf(types),
                    device.path("vram_total").isNumber() ? device.path("vram_total").asLong() : null,
                    device.path("name").asText(null), Map.copyOf(versions));
            recordVersions(node.getId(), versions);
        } catch (Exception e) {
            // 慢探测失败**不清空**已知能力：一次 60 秒周期的超时不该让这台机器
            // 在下一分钟里失去能力过滤（那会让它重新被派上跑不了的活）
            log.debug("ComfyUI 能力探测失败，保留上一次的结果: {} ({})", node.getId(), rootMessage(e));
        }
    }

    /**
     * 版本漂移<b>只告警、不淘汰，而且只在画面变了的时候说一次</b>。
     *
     * <h3>为什么不淘汰</h3>
     * 淘汰的话，一次滚动升级（先升一台）就等于把那台摘了，而它其实好好的。
     * 反过来完全不看，则是「同一个工作流在不同节点上出不同结果」这类问题
     * 唯一能被提前发现的机会 —— 那类问题事后极难归因。
     *
     * <h3>为什么按「画面」而不是按「每台每轮」告警</h3>
     * 第一版是每台节点每轮都和"主流版本"比一次，不一样就 WARN。上线当天就暴露了：
     * <b>Spark 是 ARM64 + GB10，它必须用另一套 torch/python，这个差异永远不会消失</b>，
     * 于是这条 WARN 每 60 秒刷一条、永远刷下去。真正的漂移（哪天有人升级了一台 H100）
     * 会被埋在里面 —— 和 D-028 那次「SSE 每 30 分钟一条 ERROR 全栈」是同一个病。
     * <p>
     * 改成：把整个机队的版本分布算成一个签名，<b>签名没变就一个字都不说</b>。
     * 于是异构机队每次重启只说一句，之后彻底安静；有人升级了某台，签名变了，立刻再说一句。
     *
     * <h3>为什么不再算「主流版本」</h3>
     * 并行探测时这张表是逐个填上的，在画面还不完整的时候算多数派会得出错的结论 ——
     * 上线当天真的发生了：22:38:21 说 gpu-7 偏离主流，22:39:19 又说 gpu-spark 偏离，
     * 两条互相矛盾。直接把分组结果整个打出来，不做多数派判断，也就没有这个问题。
     */
    private void recordVersions(String nodeId, Map<String, String> versions) {
        String fingerprint = versions.toString();
        String previous = versionFingerprints.put(nodeId, fingerprint);
        if (previous != null && !previous.equals(fingerprint)) {
            // 这条是真事件（有人动了这台机器），本来就极少发生，每次都值得说
            log.warn("ComfyUI 节点 {} 版本变了: {} → {}", nodeId, previous, fingerprint);
        }
        String message = driftMessage();
        if (message != null) {
            log.warn(message);
        }
    }

    /**
     * 机队版本分布变了就返回一句话，没变返回 {@code null}。
     * <p>
     * 抽成一个纯函数是为了能直接测 ——「日志会不会刷屏」这件事，
     * 用捕获 appender 去测既笨重又容易写成空过的测试。
     */
    String driftMessage() {
        Map<String, List<String>> byFingerprint = new TreeMap<>();
        versionFingerprints.forEach((id, fp) ->
                byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(id));
        byFingerprint.values().forEach(Collections::sort);

        if (byFingerprint.size() <= 1) {
            // 统一了。只有「之前警告过」才需要说一声恢复，否则启动时会为每台各说一遍"已统一"
            if (!warnedAboutDrift.getAndSet(false)) {
                return null;
            }
            lastDriftSignature.set("");
            return "ComfyUI 机队版本已统一: " + byFingerprint.keySet();
        }
        String signature = byFingerprint.toString();
        if (signature.equals(lastDriftSignature.getAndSet(signature))) {
            return null; // 画面没变，不重复说
        }
        warnedAboutDrift.set(true);
        return "ComfyUI 机队版本不一致（只告警，不影响派活）: " + byFingerprint;
    }

    private static List<ComfyUiProperties.Node> active(List<ComfyUiProperties.Node> all) {
        return all.stream().filter(n -> !n.isArchived()).toList();
    }

    private static void putIfText(Map<String, String> out, String key, JsonNode value) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            out.put(key, value.asText());
        }
    }

    /** ExecutionException 把真正的原因包在里面，直接 getMessage() 只会得到一个类名 */
    private static String rootMessage(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }
}
