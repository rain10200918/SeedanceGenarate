package org.example.seedancegenarate.engine.comfyui;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ComfyUI 节点调度。<b>纯内存计算，选节点时不发任何 HTTP。</b>
 *
 * <h3>零 IO 是这个类的硬约束</h3>
 * 这里没有 {@link ComfyUiClient} 字段，<b>物理上发不出请求</b> —— 这是比任何断言都硬的守卫。
 * 曾经的写法是在这里串行探测每一台（每台 {@code connectTimeoutMs} 3 秒），
 * 于是一台 hang 住的节点让<b>每一次提交</b>都多等 3 秒，而这个代价随节点数线性涨。
 * 谁想在这里补一次"保险起见的实时探测"，先看 {@link ComfyUiFleet} 的类注释：
 * 实时探测在羊群真正形成的窗口里什么都做不到。
 *
 * <h3>选中即计数</h3>
 * {@code pick()} 返回前会调 {@link ComfyUiFleet#markDispatched} —— 这一步让下一次 pick
 * 立刻看得见这一次，是 D-026（2026-08-28 修订）的意图落点。
 * <b>提交失败的调用方必须调 {@link #releaseDispatch}</b>，否则那次派发要等老化窗口才归还。
 *
 * <h3>淘汰理由要说得出来</h3>
 * 过滤走 {@link NodeFilter} 链，每一道都返回一句人话。选不出节点时抛的不再是
 * 一句「所有 ComfyUI 节点均不可用」，而是逐节点的理由清单 —— 那句话查不出任何东西。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComfyUiNodeScheduler {

    private final ComfyUiProperties properties;
    private final ComfyUiFleet fleet;
    /** Spring 按 {@code @Order} 注入：enabled(10) → healthy(20) → capability(30) → vram(40) */
    private final List<NodeFilter> filters;
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    /** 不限定模型（不做能力/显存过滤）。测试与「随便挑一台」的场景用 */
    public NodeSelection pick() {
        return pick(null);
    }

    public NodeSelection pick(String model) {
        return pick(model, null);
    }

    /**
     * 选节点。{@code pinnedNodeId} 非空时直接用那台，<b>绕过全部过滤（含 enabled）</b>。
     *
     * <h3>为什么指定节点要绕过 enabled</h3>
     * 这个能力唯一的用途是「新机器接进来，先不放量、把真实工作流跑通再开」——
     * 而新机器的初始状态<b>就是</b> {@code enabled=false}。要是指定也受 enabled 约束，
     * 这个功能在它唯一有用的场景里恰好不能用。
     *
     * <h3>为什么连能力/显存也绕过，只告警</h3>
     * 快照里的能力最多旧 60 秒，一台刚装完插件的机器很可能还没被探到。
     * 拿一份可能过期的数据去驳回一个管理员明确点了名的请求，
     * 会让这个功能在最需要它的时刻（刚装完、想验证）失灵。
     * 所以：照发，但把过滤链本来会说的话打进日志，失败时人一眼能看到原因。
     */
    public NodeSelection pick(String model, String pinnedNodeId) {
        if (pinnedNodeId != null && !pinnedNodeId.isBlank()) {
            NodeState pinned = fleet.node(pinnedNodeId);
            if (pinned == null) {
                throw new RuntimeException("指定的 ComfyUI 节点不存在: " + pinnedNodeId);
            }
            Map<String, String> wouldReject = new LinkedHashMap<>();
            survivors(List.of(pinned), model, false, wouldReject);
            if (!wouldReject.isEmpty()) {
                log.warn("指定节点提交，已绕过调度过滤（model={}）: {} —— {}",
                        model, pinnedNodeId, wouldReject.get(pinnedNodeId));
            }
            return select(pinned);
        }

        List<NodeState> all = new ArrayList<>(fleet.nodes());
        if (all.isEmpty()) {
            throw new RuntimeException("没有可用的 ComfyUI 节点，请检查 video.comfyui.nodes 配置");
        }

        Map<String, String> rejections = new LinkedHashMap<>();
        List<NodeState> pool = survivors(all, model, false, rejections);

        if (pool.isEmpty()) {
            // 全被淘汰时丢掉软条件（健康）重来一遍：宁可把活派给一台可能病着的机器
            // （提交失败会走 markFailed → 解冻，钱不会错），也不能让整站提交不了。
            // 硬条件（未启用 / 缺插件 / 显存不够）任何时候都不放弃 —— 放弃它们只是
            // 把一次「选不出节点」换成一次「提交过去必然失败」，更难查。
            Map<String, String> hardRejections = new LinkedHashMap<>();
            pool = survivors(all, model, true, hardRejections);
            if (!pool.isEmpty()) {
                log.warn("ComfyUI 所有节点都不健康，降级为忽略健康状态派活（model={}）: {}", model, rejections);
            } else {
                // 降级之后还是一台不剩 → 说硬原因。此时再报「探测失败」是误导：
                // 健康这条已经被主动放弃了，真正拦住的是缺插件 / 显存 / 人工关闭。
                // 一台又不健康、又缺插件的机器，运维先修哪个，全看这句话说的是哪个。
                rejections = hardRejections;
            }
        }
        if (pool.isEmpty()) {
            throw new RuntimeException(explain(model, rejections));
        }

        NodeState chosen = "round-robin".equalsIgnoreCase(properties.getScheduling())
                ? pool.get(Math.floorMod(roundRobin.getAndIncrement(), pool.size()))
                : leastLoaded(pool);

        return select(chosen);
    }

    /** 提交失败时归还名额。不调的话这次派发要占着老化窗口那么久 */
    public void releaseDispatch(NodeSelection selection) {
        fleet.releaseDispatch(selection.node().getId(), selection.dispatchToken());
    }

    /** 每台节点被哪一道过滤淘汰了（{@code null} = 通过）。管理端和错误信息共用这一份 */
    public Map<String, String> rejectionReasons(String model) {
        Map<String, String> reasons = new LinkedHashMap<>();
        survivors(new ArrayList<>(fleet.nodes()), model, false, reasons);
        return reasons;
    }

    private List<NodeState> survivors(List<NodeState> candidates, String model,
                                      boolean hardOnly, Map<String, String> rejections) {
        List<NodeState> survivors = new ArrayList<>();
        for (NodeState node : candidates) {
            String reason = null;
            for (NodeFilter filter : filters) {
                if (hardOnly && !filter.hard()) {
                    continue;
                }
                reason = filter.reject(node, model);
                if (reason != null) {
                    break; // 第一条理由就够了，不必把所有毛病都列一遍
                }
            }
            if (reason == null) {
                survivors.add(node);
            } else {
                rejections.put(node.id(), reason);
            }
        }
        return survivors;
    }

    private static String explain(String model, Map<String, String> rejections) {
        StringBuilder sb = new StringBuilder("没有能跑 ")
                .append(model == null ? "该任务" : model)
                .append(" 的 ComfyUI 节点：");
        rejections.forEach((id, reason) -> sb.append("\n  ").append(id).append(" —— ").append(reason));
        return sb.toString();
    }

    /**
     * 有效负载最小者胜。有效负载 = (队列深度 + 待发) / 权重 —— 见 {@link ComfyUiFleet#effectiveLoad}。
     * <p>
     * 平局归列表里靠前的那台。这在过去是个坑（全空闲时每次都选第一台），
     * 现在无害：待发计数在 pick 返回时就 +1，下一次的平局自然被打破。
     */
    private NodeState leastLoaded(List<NodeState> pool) {
        NodeState best = null;
        double bestLoad = Double.MAX_VALUE;
        for (NodeState node : pool) {
            double load = fleet.effectiveLoad(node);
            if (load < bestLoad) {
                bestLoad = load;
                best = node;
            }
        }
        return best;
    }

    /** 快照是调度用的观测态；下游（上传 / 提交 / 轮询）只需要 id + baseUrl 这一对 */
    private static ComfyUiProperties.Node toNode(NodeState state) {
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId(state.id());
        node.setBaseUrl(state.baseUrl());
        node.setEnabled(state.enabled());
        node.setWeight(state.weight());
        return node;
    }

    private NodeSelection select(NodeState state) {
        return new NodeSelection(toNode(state), fleet.markDispatched(state.id()));
    }

    /** 一次节点选择及其待发预约；提交失败必须用同一个实例精确释放。 */
    public record NodeSelection(ComfyUiProperties.Node node, long dispatchToken) {
    }
}
