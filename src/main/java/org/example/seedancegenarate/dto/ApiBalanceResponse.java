package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** Stable external API view of the current API Key owner's wallet. */
public record ApiBalanceResponse(
        BigDecimal available,
        BigDecimal frozen,
        BigDecimal total,
        String currency
) {
}
