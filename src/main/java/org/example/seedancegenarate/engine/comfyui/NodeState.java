package org.example.seedancegenarate.engine.comfyui;

import java.util.Map;
import java.util.Set;

/**
 * 一台 ComfyUI 节点在某一瞬间的状态。<b>不可变</b> —— 探测器算出新的一份整体替换，
 * 读侧（{@code pick()}）永远看不到半更新的中间态。
 *
 * <h3>enabled 和 healthy 必须是两个字段</h3>
 * {@code enabled} 是<b>人的意愿</b>（配置/管理端），{@code healthy} 是<b>机器的状态</b>（探测器）。
 * 合成一个的后果：运维手动禁用一台去维护，探测器发现它活着又给打开了；
 * 或者反过来，一次网络抖动把节点标死，人再也不知道该不该恢复。
 * <b>探测器只写 healthy 那一半，永远不碰 enabled。</b>
 *
 * <h3>能力「未知」和能力「为空」是两回事</h3>
 * {@code capabilities == null} = 还没探到（启动后头一分钟、或 /object_info 拉失败），
 * 此时<b>不做能力过滤</b>；{@code capabilities.isEmpty()} = 探到了、确实什么都没有。
 * 混成一个的后果是一次 /object_info 超时就把整台机器判成"什么都跑不了"。
 */
public record NodeState(
        String id,
        String baseUrl,
        /** 人的意愿：管理端开关 */
        boolean enabled,
        /** 已退役。行和快照都留着（否则它上面的在途任务会查不到节点被判死），只是不探不派 */
        boolean archived,
        /** 给人看的备注 */
        String remark,
        /** 相对算力，H100 = 1.0，Spark 实测 2.03x/2.34x 慢 → 0.45 */
        double weight,
        /** 机器的状态：连续探测失败到阈值才判 false */
        boolean healthy,
        /** 来自 GET /prompt 的 exec_info.queue_remaining（运行中 + 排队中） */
        int queueDepth,
        int consecutiveFailures,
        String lastError,
        long lastProbedAt,
        /** 上一次快探测的往返耗时。跳板机开始劣化时这个数先动，队列深度还看不出来 */
        long probeLatencyMs,

        // ---- 以下由慢探测填充（VS-2）；null = 还不知道，不是"没有" ----
        Set<String> capabilities,
        Long vramTotal,
        String gpuName,
        Map<String, String> versions
) {

    /**
     * 启动时的乐观初值：还没探过就先当它是好的。
     * <p>
     * 反过来（初值 unhealthy）会让后端启动后的头一个探测周期内所有提交全部失败 ——
     * 而重启是最常发生的运维动作。
     */
    public static NodeState initial(ComfyUiProperties.Node node) {
        return new NodeState(node.getId(), node.getBaseUrl(), node.isEnabled(), node.isArchived(), node.getRemark(), node.effectiveWeight(),
                true, 0, 0, null, 0L, 0L, null, null, null, null);
    }

    /** 探测成功：清零失败计数，保留慢探测的字段 */
    public NodeState probedOk(int queueDepth, long at, long latencyMs) {
        return new NodeState(id, baseUrl, enabled, archived, remark, weight, true, queueDepth, 0, null, at, latencyMs,
                capabilities, vramTotal, gpuName, versions);
    }

    /**
     * 探测失败：失败计数 +1，达到阈值才翻 healthy。
     * <p>
     * 一次失败就摘掉的话，一次网络抖动能把健康节点关在门外；而计数必须是<b>连续</b>的
     * （一次成功即清零），否则跑够久的节点迟早都会被累计到阈值。
     */
    public NodeState probeFailed(String error, int unhealthyAfter, long at) {
        int failures = consecutiveFailures + 1;
        return new NodeState(id, baseUrl, enabled, archived, remark, weight, failures < unhealthyAfter,
                queueDepth, failures, error, at, probeLatencyMs,
                capabilities, vramTotal, gpuName, versions);
    }

    /**
     * 慢探测（{@code /object_info} + {@code /system_stats}）的结果。
     * <p>
     * 只在<b>成功</b>时调用 —— 慢探测失败不许把已知的能力清回 null，
     * 否则一次 60 秒周期的超时就让这台机器在下一分钟里失去能力过滤。
     */
    public NodeState withCapabilities(Set<String> capabilities, Long vramTotal,
                                      String gpuName, Map<String, String> versions) {
        return new NodeState(id, baseUrl, enabled, archived, remark, weight, healthy, queueDepth, consecutiveFailures,
                lastError, lastProbedAt, probeLatencyMs, capabilities, vramTotal, gpuName, versions);
    }

    /**
     * 配置侧（地址 / 开关 / 权重）变了，观测侧原样保留 ——
     * <b>但地址变了是例外：那等于换了一台机器，观测态一概不继承。</b>
     * <p>
     * 不清零的话有个很难查的后果：一台节点因为地址填错而连续失败、退避到 60 秒一探，
     * 运维在管理端把地址改对了，它却因为失败计数还挂着而继续按 60 秒退避 ——
     * 人改完看不到任何变化，只会以为「改了没用」。能力和显存同理：
     * 那是<b>旧机器</b>的能力，拿它给新地址做路由过滤是错的。
     */
    public NodeState withConfig(ComfyUiProperties.Node node) {
        if (!java.util.Objects.equals(baseUrl, node.getBaseUrl())) {
            return initial(node);
        }
        return new NodeState(node.getId(), node.getBaseUrl(), node.isEnabled(), node.isArchived(),
                node.getRemark(), node.effectiveWeight(), healthy, queueDepth, consecutiveFailures, lastError, lastProbedAt, probeLatencyMs,
                capabilities, vramTotal, gpuName, versions);
    }

    /**
     * 这一轮该不该探它 —— <b>探测侧的熔断</b>。
     *
     * <h3>熔断的是「多久探一次」，不是「派不派活」</h3>
     * 派不派活由 {@link #healthy} 决定（{@code HealthyFilter}），和这里完全分开。
     * 合成一件事的后果是致命的：被熔断的节点从此不再被探测，
     * 于是它<b>永远不会恢复</b>，修好了也没人知道。
     *
     * <h3>确诊前全速，确诊后指数退避，一次成功立刻全速</h3>
     * 失败次数没到 {@code unhealthyAfter} 之前不退避 —— 那几次正是在分辨
     * 「网络抖了一下」还是「真死了」，退避会把这个判断拖慢好几倍。
     * 确诊之后按 2 的幂退避、到 {@code maxIntervalMs} 封顶：3s→6→12→24→48→60→60…，
     * 约 100 秒后稳定在 60 秒一探。
     * <p>
     * 恢复<b>不搞半开</b>：一次成功就回到全速。经典熔断器的半开是为了保护下游，
     * 而这里的下游只是一个 37 字节的 {@code GET /prompt}，探它没有任何代价，
     * 慢恢复只会让一台已经修好的机器白白闲着。
     */
    public boolean dueForProbe(long now, long baseIntervalMs, long maxIntervalMs, int unhealthyAfter) {
        int threshold = Math.max(unhealthyAfter, 1);
        if (consecutiveFailures < threshold) {
            return true;
        }
        int steps = Math.min(consecutiveFailures - threshold + 1, 20); // 20 以上就溢出了
        long base = Math.max(baseIntervalMs, 1);
        return now - lastProbedAt >= Math.min(base << steps, Math.max(maxIntervalMs, base));
    }
}
