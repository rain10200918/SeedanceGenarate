package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金流入订单：用户充值 / 管理员加钱统一走这张表（channel 区分来源），
 * 入账 = 订单 + 流水 + 余额同一事务。order_no 幂等，admin 渠道 channel_txn_id = order_no。
 */
@Data
@TableName("recharge_order")
public class RechargeOrder {
    public static final String CHANNEL_ADMIN = "admin";
    public static final String CHANNEL_ALIPAY = "alipay";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private String channel;
    private String channelTxnId;
    /** 管理员/渠道请求幂等键；与 user_id 组成唯一键 */
    private String requestId;
    private BigDecimal amount;
    private String status;
    private Long operatorId;
    private String operatorName;
    private String reason;
    private LocalDateTime paidAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
