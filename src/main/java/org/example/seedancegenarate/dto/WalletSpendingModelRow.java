package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包消费按模型聚合行（Mapper 内部结果）。 */
@Data
public class WalletSpendingModelRow {
    private String model;
    private BigDecimal amount;
    private Long taskCount;
}
