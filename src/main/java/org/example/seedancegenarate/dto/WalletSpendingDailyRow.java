package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包消费按日聚合行（Mapper 内部结果）。 */
@Data
public class WalletSpendingDailyRow {
    private String date;
    private BigDecimal amount;
}
