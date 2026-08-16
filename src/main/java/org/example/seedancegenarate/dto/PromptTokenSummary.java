package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 提示词优化 token 消耗汇总（管理端）。全部从明细现算，不建计数器表。
 */
public record PromptTokenSummary(
        /** 今日调用次数（全部状态） */
        long todayCalls,
        /** 今日成功 token 消耗 */
        long todayTokens,
        /** 今日失败次数 */
        long todayFailed,
        /** 累计调用次数（全部状态） */
        long totalCalls,
        /** 累计成功 token 消耗 */
        long totalTokens,
        /** 累计失败次数 */
        long totalFailed,
        /** 累计输入 token */
        long inputTokens,
        /** 累计输出 token */
        long outputTokens,
        /** 按场景分布（成功调用） */
        List<SceneTokens> byScene,
        /** 近 7 天成功消耗趋势（按天，缺天补 0） */
        List<DayTokens> dailyTrend
) {
    /** 单场景聚合：调用次数 + 成功 token */
    public record SceneTokens(String scene, long calls, long tokens) {
    }

    /** 单天聚合：日期 + 调用次数 + 成功 token */
    public record DayTokens(String date, long calls, long tokens) {
    }
}
