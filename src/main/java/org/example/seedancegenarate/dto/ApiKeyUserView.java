package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 属主用户视角的 API Key（GET /api/user/api-keys）：钥匙属性 + 调用聚合（api_call_log 现算）。
 */
public record ApiKeyUserView(
        Long id,
        String keyPrefix,
        String name,
        String status,
        LocalDateTime lastUsedAt,
        LocalDateTime createTime,
        long callCount,
        long successCount,
        long failedCount,
        long rejectedCount,
        BigDecimal totalCost
) {
}
