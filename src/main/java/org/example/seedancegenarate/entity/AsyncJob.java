package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 持久化异步作业（行级租约领取，多 Worker 并行安全）。 */
@Data
@TableName("async_job")
public class AsyncJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;
    /** 业务幂等键；(job_type, biz_key) 唯一，重复入队直接跳过。 */
    private String bizKey;
    /** 轻量 JSON 参数。 */
    private String payload;
    /** READY / RUNNING / SUCCEEDED / DEAD */
    private String status;
    private Integer attempts;
    private Integer maxAttempts;
    /** 退避后的下次可领取时间。 */
    private LocalDateTime availableAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String lastError;
    @TableField(fill = FieldFill.INSERT,value="created_at")
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE,value="updated_at")
    private LocalDateTime updateTime;

    public static final String STATUS_READY = "READY";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_DEAD = "DEAD";
}
