package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户消费明细：SETTLE 流水关联任务，金额使用账本事实。 */
@Data
public class WalletSpendingView {
    private Long transactionId;
    private Long taskId;
    private String taskBusinessId;
    private String model;
    private String provider;
    private Integer duration;
    private String taskStatus;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private BigDecimal frozenAfter;
    private String remark;
    private LocalDateTime createTime;
}
