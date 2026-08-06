package org.example.seedancegenarate.dto;

import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.VideoTask;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单个用户的画像详情（管理端展开行）：
 * 任务/API 调用状态分布 + 消费 + 最近记录，全部现算聚合。
 */
public record UserProfileDetail(
        Long userId,
        String username,
        long taskTotal,
        long taskSuccess,
        long taskFailed,
        long taskProcessing,
        long apiCallTotal,
        long apiCallSuccess,
        long apiCallFailed,
        long apiCallRejected,
        BigDecimal totalCost,
        BigDecimal monthCost,
        List<VideoTask> recentTasks,
        List<ApiCallLog> recentApiCalls
) {
}
