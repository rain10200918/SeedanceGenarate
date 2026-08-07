package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型计价配置（管理端在线改价）。
 * 计费链三级回退：模型精确配置 → 提供方默认（model=''）→ yaml 默认。
 */
@Data
@TableName("price_config")
public class PriceConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String provider;
    /** 模型 id；空串 = 提供方默认价 */
    private String model;
    /** PER_SECOND 按秒 / FLAT 按次固定 */
    private String billingType;
    private BigDecimal unitPrice;
    private String currency;
    private Boolean enabled;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
