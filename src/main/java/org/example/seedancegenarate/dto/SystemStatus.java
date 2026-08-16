package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 管理端「系统状态」聚合：运行健康度一览（不用 Prometheus/Grafana 也能盯）。
 * 数据全部从 DB 现查（与 MetricsExportTask 同源）。
 */
public record SystemStatus(
        /** 生成中任务（按引擎） */
        List<ProviderCount> processingByProvider,
        /** 卡死任务（超过超时阈值仍 PROCESSING，含详情） */
        List<StuckTask> stuckTasks,
        /** 死信作业数（重试耗尽，需人工介入） */
        long deadJobs,
        /** 近 5 分钟成功/失败 */
        long success5m,
        long failed5m,
        /** 近 5 分钟成功率（0-100，无样本为 null） */
        Double successRate5m,
        /** ComfyUI 节点状态（复用健康探测） */
        List<NodeStatus> nodes,
        /** 数据生成时间 */
        String updatedAt
) {
    public record ProviderCount(String provider, long count) {
    }

    public record StuckTask(String taskId, String provider, long ageMinutes) {
    }

    public record NodeStatus(String id, boolean online, int queueLoad, long latencyMs, String error) {
    }
}
