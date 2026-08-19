package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包消费概览的数据库聚合行。 */
@Data
public class WalletSpendingTotals {
    private BigDecimal totalSpent;
    private BigDecimal monthSpent;
    private Long taskCount;
    private Long successCount;
}
