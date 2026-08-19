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
 * 用户钱包。余额真相在 balance_transaction 流水（可累加对账），
 * 本表是缓存 + 行锁位，变更必须与流水同事务。
 */
@Data
@TableName("wallet")
public class Wallet {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private BigDecimal balance;
    private BigDecimal frozen;
    private Integer version;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
