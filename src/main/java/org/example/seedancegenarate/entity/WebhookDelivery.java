package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * webhook 投递记录。同任务同状态只投递一次（(taskId, status) 唯一），
 * 失败按 nextRetryAt 定时重试（attempts 计数）。
 */
@Data
@TableName("webhook_delivery")
public class WebhookDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private Long apiKeyId;
    /** 推送的任务终态（SUCCESS / FAILED） */
    private String status;
    /** 推送体 JSON */
    private String payload;
    /** 对方响应码 */
    private Integer httpCode;
    private Integer attempts;
    private LocalDateTime nextRetryAt;
    /** 1=已投递成功 */
    private Boolean delivered;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
