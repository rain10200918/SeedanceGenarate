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
 * 余额流水账本（唯一真相）。一切金额变动先写流水（biz_key 唯一约束 = 幂等），
 * 再更新 wallet，两者同事务。
 */
@Data
@TableName("balance_transaction")
public class BalanceTransaction {
    public static final String TYPE_RECHARGE = "RECHARGE";          // 渠道充值
    public static final String TYPE_ADMIN_CREDIT = "ADMIN_CREDIT";  // 管理员加钱
    public static final String TYPE_REWARD = "REWARD";              // 奖励（分享等）
    public static final String TYPE_FREEZE = "FREEZE";              // 任务提交冻结
    public static final String TYPE_SETTLE = "SETTLE";              // 任务成功结算
    public static final String TYPE_RELEASE = "RELEASE";            // 任务失败解冻
    public static final String TYPE_REFUND = "REFUND";              // 退款
    public static final String TYPE_ADJUST = "ADJUST";              // 人工调整

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    /** 账本净资产变动：冻结内部转移记 0，结算为负，入账/解冻为正 */
    private BigDecimal amount;
    /** 冻结/结算/解冻的预授权金额；与 amount 的净资产口径分离 */
    private BigDecimal holdAmount;
    private BigDecimal balanceAfter;
    /** 变更后的冻结余额；V14 前的历史流水可能为空 */
    private BigDecimal frozenAfter;
    private String bizKey;
    private Long taskId;
    private String refOrderNo;
    private Long operatorId;
    private String operatorName;
    private Long couponId;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
