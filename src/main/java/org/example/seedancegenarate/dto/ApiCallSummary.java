package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * API 调用汇总（GET /api/admin/api-calls/summary）：状态分布 + 拒绝原因分布。
 */
public record ApiCallSummary(
        long total,
        long success,
        long failed,
        long rejected,
        List<ErrorCodeCount> byErrorCode
) {
    public record ErrorCodeCount(String errorCode, long count) {
    }
}
