package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 分镜流水线（可复用的批量制作流程；运行/重试/复制） */
@Data
@TableName("pipeline")
public class Pipeline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 运行时统一模型提供方；空=系统默认 */
    private String provider;
    /** 运行时统一模型；空=提供方默认 */
    private String model;
    /** DRAFT/RUNNING/DONE/PARTIAL_FAILED */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
