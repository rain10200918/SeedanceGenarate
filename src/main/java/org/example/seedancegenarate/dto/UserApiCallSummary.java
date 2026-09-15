package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.util.List;

public record UserApiCallSummary(long total, long received, long success, long failed, long rejected,
                                 BigDecimal totalCost, String currency, List<ErrorCodeCount> byErrorCode) {
    public record ErrorCodeCount(String errorCode, long count) {}
}
