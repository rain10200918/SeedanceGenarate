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
    private int readTimeoutMs = 60000;
    /** 访问令牌：所有对 ComfyUI 的请求统一带 X-Comfy-Token（nginx 入口校验）。 */
    private String accessToken;
    private List<Node> nodes = new ArrayList<>();

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
