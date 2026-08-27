package org.example.seedancegenarate.engine.comfyui;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ComfyUI 节点调度：实例同构，故只做负载均衡，无能力路由。
 * least-queue：查各节点 /queue 取最闲的（顺带健康检查）；round-robin：轮询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComfyUiNodeScheduler {

    private final ComfyUiProperties properties;
    private final ComfyUiClient client;
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    public ComfyUiProperties.Node pick() {
        List<ComfyUiProperties.Node> nodes = properties.enabledNodes();
        if (nodes.isEmpty()) {
            throw new RuntimeException("没有可用的 ComfyUI 节点，请检查 video.comfyui.nodes 配置");
        }
        if ("round-robin".equalsIgnoreCase(properties.getScheduling())) {
            int idx = Math.floorMod(roundRobin.getAndIncrement(), nodes.size());
            return nodes.get(idx);
        }
        return leastQueue(nodes);
    }

    private ComfyUiProperties.Node leastQueue(List<ComfyUiProperties.Node> nodes) {
        ComfyUiProperties.Node best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (ComfyUiProperties.Node node : nodes) {
            try {
                // 这里刻意用更紧的 connectTimeoutMs（3s）而不是 statusTimeoutMs：选节点在提交路径上、
                // 用户正等着，宁可跳过一台反应慢的节点，也不能让每次提交多等几秒。
                int load = client.queueLoad(node.getBaseUrl(), properties.getConnectTimeoutMs());
                if (load < bestLoad) {
                    bestLoad = load;
                    best = node;
                }
            } catch (Exception e) {
                log.warn("ComfyUI 节点不可用，跳过: {} ({})", node.getId(), e.getMessage());
            }
        }
        if (best == null) {
            throw new RuntimeException("所有 ComfyUI 节点均不可用");
        }
        return best;
    }
}
