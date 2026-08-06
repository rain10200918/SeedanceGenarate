package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cost_record")
public class CostRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long taskId;
    private String seedanceTaskId;
    private Integer duration;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String currency;
    private String bizType;
    /** 生成提供方：seedance / comfyui */
    private String provider;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
