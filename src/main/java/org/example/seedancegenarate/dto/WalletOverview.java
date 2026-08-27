package org.example.seedancegenarate.dto;

import java.math.BigDecimal;

/** 用户钱包概览：可用余额、冻结金额、总额，以及 1元=100算力点 换算后的算力点数。 */
public record WalletOverview(
        BigDecimal balance,
        BigDecimal frozen,
        BigDecimal total,
        String currency,
        Long points,
        Long frozenPoints,
        Long totalPoints
) {
    public WalletOverview(BigDecimal balance, BigDecimal frozen, BigDecimal total, String currency) {
        this(
                balance,
                frozen,
                total,
                currency,
                balance != null ? balance.multiply(BigDecimal.valueOf(100)).longValue() : 0L,
                frozen != null ? frozen.multiply(BigDecimal.valueOf(100)).longValue() : 0L,
                total != null ? total.multiply(BigDecimal.valueOf(100)).longValue() : 0L
        );
    }
}
