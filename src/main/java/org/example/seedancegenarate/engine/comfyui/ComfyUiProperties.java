package org.example.seedancegenarate.engine.comfyui;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    /** 按 ID 查节点（轮询时用任务上记录的 node_id 找回同一台） */
    public Node findNode(String id) {
        if (id == null) {
            return null;
        }
        for (Node n : nodes) {
            if (id.equals(n.getId())) {
                return n;
            }
        }
        return null;
    }

    @Data
    public static class Node {
        /** 节点 ID，写入任务用于亲和 */
        private String id;
        /** ComfyUI 基础地址，如 http://127.0.0.1:8188 */
        private String baseUrl;
        private boolean enabled = true;
    }
}
