package org.example.seedancegenarate.engine.comfyui;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ComfyUI 多实例配置。实例同构（同一套模型），故只配地址、不配能力。
 */
@Data
@Component
@ConfigurationProperties(prefix = "video.comfyui")
public class ComfyUiProperties {

    /** 调度策略：least-queue（队列最闲）| round-robin（轮询） */
    private String scheduling = "least-queue";
    private int connectTimeoutMs = 3000;
    /** 提交 / 上传 / 下载素材的时限：载荷可达几十 MB，必须给足 */
    private int readTimeoutMs = 60000;
    /**
     * 状态查询（/history、/queue）的时限。
     * <p>
     * 和提交共用一个超时是个陷阱：一个是读几 KB JSON，一个是传几十 MB 素材。
     * 共用的后果是一台 hang 住的节点能让对账的单条任务阻塞满 60 秒——而 Hutool 的
     * {@code timeout()} 同时设 connect 和 read，连 SYN 无人应答（宿主机死掉）也要等满。
     */
    private int statusTimeoutMs = 5000;
    /**
     * 这台 ComfyUI 是否真的会回调。
     * <p>
     * <b>默认 false，因为原生 ComfyUI 没有这个能力</b>：{@code /prompt} 只读
     * {@code prompt}/{@code client_id}/{@code extra_data}，多余的顶层字段静默丢弃，
     * 完成事件走 WebSocket。2026-08-26 在生产机上确认 {@code server.py}、{@code api_server/}、
     * {@code middleware/} 对 webhook 零命中 —— 我们发出去的 {@code webhook_url} 从来没人读，
     * 而代码却据此声明自己是事件驱动，把任务踢出了轮询器，平均多等 45 秒。
     * <p>
     * 装了 JobNotify 这类扩展后置为 true，即可一行配置切回事件驱动。
     */
    private boolean webhookSupported = false;
    /**
     * {@code /queue} 响应的缓存时长（毫秒）；0 = 不缓存。
     * <p>
     * 它的响应里带着队列中<b>每个 prompt 的完整工作流 JSON</b>，可能几 MB。
     * 丢失判定会对每个 history 为空的任务查一次队列，一轮里同节点上的多条任务
     * 拿到的必然是同一份，没必要各取一遍。
     */
    private long queueCacheMs = 3000;
    /** 访问令牌：所有对 ComfyUI 的请求统一带 X-Comfy-Token（nginx 入口校验）。 */
    private String accessToken;
    /**
     * 后台探测周期（毫秒）。探的是 {@code GET /prompt}（响应约 37 字节），不是 {@code /queue}。
     * <p>
     * 这个数只影响「对方队列的真值有多新」，不影响「看不看得见自己刚发出去的」——
     * 后者由待发计数同步保证（见 {@link ComfyUiFleet}）。
     */
    private int probeIntervalMs = 3000;
    /**
     * 待发计数的老化窗口（毫秒）。必须 ≥ 探测周期 + 最慢一次素材上传的耗时，
     * 否则 prompt 还没落地、计数就先没了，那一瞬间这台节点会显得比实际闲。
     * 启动时按 {@code probeIntervalMs × 2} 兜底。
     */
    private long pendingAgingMs = 15000;
    /**
     * 连续探测失败几次才判 unhealthy。<b>连续</b>是关键：一次成功即清零，
     * 否则跑够久的节点迟早会被累计到阈值。
     */
    private int unhealthyAfterFailures = 3;
    /**
     * 能力探测周期（毫秒）：{@code /object_info}（MB 级）+ {@code /system_stats}。
     * 装插件 / 重启才会变，不需要跟队列深度一个频率。
     */
    private int capabilityProbeIntervalMs = 60000;
    /**
     * 已确诊不健康的节点，探测间隔最多退到多久（毫秒）—— <b>探测侧的熔断上限</b>。
     * <p>
     * 熔断的是「多久探一次」不是「派不派活」（后者由 healthy 管）。
     * 3s→6→12→24→48→60 封顶，约 100 秒后稳定；一次成功立刻回到全速，
     * 所以一台修好的机器最多晚这么久被发现。
     */
    private int probeBackoffMaxMs = 60000;
    /**
     * 每个模型至少要多少显存（GiB）。<b>没写的模型一律不过滤</b> ——
     * 宁可漏拦（退回今天的行为：提交过去 OOM），也不能因为忘填一个数就把所有节点判成跑不了。
     * <p>
     * 判据是 {@code vram_total} 不是 {@code vram_free}：32 GiB 的卡再空闲也装不下 53 GiB 的链路。
     */
    private Map<String, Double> modelMinVramGib = new LinkedHashMap<>();
    private List<Node> nodes = new ArrayList<>();

    /**
     * 下限 1000ms：Hutool 的 {@code timeout(0)} 会落到 Java 的 {@code setConnectTimeout(0)}，
     * 那是<b>无限等待</b>——配错一个 0 比根本不改这个超时还糟。
     */
    public int getStatusTimeoutMs() {
        return Math.max(statusTimeoutMs, 1000);
    }

    /** 仅返回启用的节点 */
    public List<Node> enabledNodes() {
        List<Node> list = new ArrayList<>();
        for (Node n : nodes) {
            if (n.isEnabled()) {
                list.add(n);
            }
        }
        return list;
    }

    // findNode 已移到 ComfyUiFleet：节点清单的真相在 comfy_node 表，不在这里。
    // 留一个读 yaml 的 findNode 会让「两个来源同时是合法的」——那是最难查的一类 bug，
    // 症状是管理端改完地址、轮询却还在往老地址发请求。删掉，让编译器挡住。

    /** 下限 = 探测周期 × 2：配小了会让 prompt 还没落地、待发计数就先老化掉 */
    public long getPendingAgingMs() {
        return Math.max(pendingAgingMs, Math.max(probeIntervalMs, 1) * 2L);
    }

    @Data
    public static class Node {
        /** 节点 ID，写入任务用于亲和 */
        private String id;
        /** ComfyUI 基础地址，如 http://127.0.0.1:8188 */
        private String baseUrl;
        private boolean enabled = true;
        /**
         * 已归档（退役）。<b>归档不等于删除</b>：行留着、快照里也留着，
         * 只是探测器不再探它、管理端默认不列它、调度不派活给它。
         * <p>
         * 留着是因为 {@code ComfyUiEngine.poll()} 要靠 {@code node_id} 找回处理该任务的机器 ——
         * 查不到就直接判 FAILED，那台上正在跑的任务会全部当场死掉。
         */
        private boolean archived = false;
        /** 给人看的：这台在哪、谁维护、为什么关着 */
        private String remark;
        /**
         * 相对算力。H100 = 1.0；Spark（GB10，ARM64）实测 z-image-turbo 2.03x、
         * minimax-h3-t2v-hd 2.34x 慢，取 0.45。
         * <p>
         * 差异是均匀的，所以一个节点级权重就够，<b>不要按模型分权重</b>。
         */
        private double weight = 1.0;

        /**
         * 下限 0.01。配 0 或负数会让 {@code queue / weight} 除出 0 或负数 ——
         * 那台节点从此永远"最闲"，全站的活会一股脑压到它身上，
         * 而这只是有人在 yaml 里少打了一个小数点。
         */
        public double effectiveWeight() {
            return Math.max(weight, 0.01);
        }
    }
}
