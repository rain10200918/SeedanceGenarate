package org.example.seedancegenarate.dto;

/**
 * 单个 ComfyUI 节点的健康检测结果。
 * 数据源：GET /queue（队列深度 + 连通性）+ GET /system_stats（GPU/显存）。
 */
public record NodeHealth(
        String id,
        String baseUrl,
        boolean online,
        long latencyMs,
        int queueLoad,
        String gpuName,
        Long vramTotal,
        Long vramFree,
        String error
) {
}
