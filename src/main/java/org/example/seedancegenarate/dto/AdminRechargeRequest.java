package org.example.seedancegenarate.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 管理员加钱请求（来源留痕：操作人自动取当前管理员，原因必填） */
@Data
public class AdminRechargeRequest {
    private Long userId;
    private BigDecimal amount;
    /** 管理端重试必须复用同一个 requestId，不能每次生成新充值单 */
    private String requestId;
    private String reason;
}
