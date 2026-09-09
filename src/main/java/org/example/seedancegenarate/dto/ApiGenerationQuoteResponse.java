package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** 报价金额即真实提交时将冻结的金额。 */
public record ApiGenerationQuoteResponse(
        String provider,
        String model,
        Integer duration,
        String outputType,
        BigDecimal unitPrice,
        BigDecimal amount,
        String currency
) {
}
