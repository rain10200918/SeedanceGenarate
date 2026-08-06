package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人中心统计（GET /api/user/stats）。全部由 video_task / app_user 聚合现算，不建计数器表。
 */
public record UserStatsResponse(
        BigDecimal totalCost,
        BigDecimal monthCost,
        long taskTotal,
        long taskSuccess,
        long taskFailed,
        double successRate,
        List<DailyCount> dailyTrend,
        List<ModelStat> modelStats,
        List<RecentTask> recentTasks
) {
    public record DailyCount(String date, long count) {
    }

    public record ModelStat(String model, String label, long count, BigDecimal cost) {
    }

    public record RecentTask(String taskId, String model, String status, BigDecimal costAmount,
                             LocalDateTime createTime) {
    }
}
