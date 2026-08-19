package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.util.List;

/** 用户消费概览：真实消费来自 SETTLE 流水，任务统计来自 video_task。 */
public record WalletSpendingSummary(
        BigDecimal totalSpent,
        BigDecimal monthSpent,
        long taskCount,
        long successCount,
        List<ModelSpending> byModel,
        List<DailySpending> dailyTrend
) {
    public record ModelSpending(String model, BigDecimal amount, long taskCount) {
    }

    public record DailySpending(String date, BigDecimal amount) {
    }
}
