package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理看板（GET /api/admin/dashboard）：全局任务/消费概览，全部聚合现算。
 */
public record AdminDashboardResponse(
        long totalTask,
        long totalSuccess,
        long totalFailed,
        double successRate,
        long todayTask,
        long todaySuccess,
        long todayFailed,
        BigDecimal todayCost,
        BigDecimal monthCost,
        List<DailyCount> dailyTrend,
        List<ModelStat> modelStats,
        List<TopUser> topUsers
) {
    public record DailyCount(String date, long count) {
    }

    public record ModelStat(String model, String label, long count, BigDecimal cost) {
    }

    public record TopUser(Long userId, String username, BigDecimal cost) {
    }
}
