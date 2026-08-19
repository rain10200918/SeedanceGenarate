package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** 用户钱包概览：可用、冻结、总额。 */
public record WalletOverview(
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal total,
        String currency
) {
}
