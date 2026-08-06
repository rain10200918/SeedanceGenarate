package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** 用户管理页全局统计卡（服务端聚合，非当前页） */
public record UserSummary(
        long total,
        long adminCount,
        long userCount,
        long todayNew,
        BigDecimal totalCost
) {
}
