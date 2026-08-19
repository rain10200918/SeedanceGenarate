package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包对账差异行：流水合计 vs 总资产（balance + frozen） */
@Data
public class WalletReconcileDiff {
    private Long userId;
    private BigDecimal ledgerTotal;
    private BigDecimal walletTotal;
}
