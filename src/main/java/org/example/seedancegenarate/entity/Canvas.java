package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 无限画布：一块可无限延展的工作区，装节点与连线（独立于分镜流水线） */
@Data
@TableName("canvas")
public class Canvas {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 视口 {x,y,zoom}（JSON 字符串） */
    private String viewport;
    /** 乐观并发版本号：每次增量保存 +1，客户端带 baseVersion 做 CAS */
    private Long version;
    /** 最后应用的保存幂等键；重复提交据此识别重放 */
    private String lastMutationId;
    /** DRAFT/RUNNING/DONE/PARTIAL_FAILED */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
