package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API 调用明细管理端视图（含钥匙前缀与属主用户名，免前端多次查询）。
 * 请求参数 / 耗时分段 / UA 供展开行展示，列表页只取摘要。
 */
public record ApiCallLogView(
        Long id,
        String requestId,
        String keyPrefix,
        String username,
        String taskId,
        String endpoint,
        String model,
        String provider,
        String status,
        Integer httpCode,
        String errorCode,
        String errorMsg,
        BigDecimal costAmount,
        Integer imageCount,
        Integer duration,
        String ratio,
        Double megapixels,
        String userAgent,
        Long queuedMs,
        Long generateMs,
        Long totalMs,
        String clientIp,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
