package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端资金流水视图：联查 app_user.username，避免前端逐条补查用户。 */
@Data
public class BalanceTransactionView {
    private Long id;
    private Long userId;
    private String username;
    private String type;
    private BigDecimal amount;
    private BigDecimal holdAmount;
    private BigDecimal balanceAfter;
    private BigDecimal frozenAfter;
    private String bizKey;
    private Long taskId;
    private String refOrderNo;
    private Long operatorId;
    private String operatorName;
    private Long couponId;
    private String remark;
    private LocalDateTime createTime;
}
