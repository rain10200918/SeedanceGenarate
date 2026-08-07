package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.service.NodeHealthService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 节点健康检测实现。每次调用实时探测：/queue（连通性 + 队列深度 + 延迟）
 * + /system_stats（GPU 型号 / 显存）。节点少，直接实时查，不做缓存。
 */
@Service
@RequiredArgsConstructor
public class NodeHealthServiceImpl implements NodeHealthService {

    private final ComfyUiProperties properties;
    private final ComfyUiClient client;

    @Override
    public List<NodeHealth> checkAll() {
        return properties.enabledNodes().parallelStream()
                .map(this::checkOne)
                .toList();
    }

    private NodeHealth checkOne(ComfyUiProperties.Node node) {
        long start = System.nanoTime();
        try {
            int load = client.queueLoad(node.getBaseUrl(), properties.getConnectTimeoutMs());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            JsonNode stats = client.getSystemStats(node.getBaseUrl(), properties.getConnectTimeoutMs());
            JsonNode device = stats.path("devices").path(0);
            return new NodeHealth(
                    node.getId(),
                    node.getBaseUrl(),
                    true,
                    latencyMs,
                    load,
                    device.path("name").asText(""),
                    numOrNull(device.path("vram_total")),
                    numOrNull(device.path("vram_free")),
                    null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new NodeHealth(node.getId(), node.getBaseUrl(), false,
                    latencyMs, -1, null, null, null, e.getMessage());
        }
    }

    private static Long numOrNull(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }
}
