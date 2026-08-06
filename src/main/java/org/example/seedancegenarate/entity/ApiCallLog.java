package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对外 API 调用明细（唯一真相）。统计全部从这里现算：模型调用次数、成功率、
 * 拒绝分布、按 key 消费、耗时分段——不建计数器表，避免与明细漂移。
 * <p>
 * 状态流转：接单时 RECEIVED → 终态 SUCCESS / FAILED；被拒（校验/鉴权/限流）为 REJECTED。
 */
@Data
@TableName("api_call_log")
public class ApiCallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 幂等键 / 追踪号（客户端 Idempotency-Key），唯一 */
    private String requestId;
    private Long apiKeyId;
    /** 冗余，按人查 */
    private Long userId;
    /** 关联 video_task.task_id；被拒请求为空 */
    private String taskId;
    private String endpoint;
    private String method;
    /** 请求的模型标识（被拒也有值 → 统计维度） */
    private String model;
    private String provider;
    private Integer imageCount;
    private Integer duration;
    private String ratio;
    private Double megapixels;
    /** RECEIVED / SUCCESS / FAILED / REJECTED */
    private String status;
    /** 被拒时记录 400/401/403/429 */
    private Integer httpCode;
    private String errorCode;
    private String errorMsg;
    private String clientIp;
    private String userAgent;
    /** 从 cost_record 冗余，对账免 join */
    private BigDecimal costAmount;
    /** 提交 → 引擎接收 */
    private Long queuedMs;
    /** 引擎接收 → 终态 */
    private Long generateMs;
    /** 提交 → 终态 */
    private Long totalMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
