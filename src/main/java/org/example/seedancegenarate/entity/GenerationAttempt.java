package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一轮远程生成的提交事实；传输重试始终复用同一行。 */
@Data
@TableName("generation_attempt")
public class GenerationAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoTaskId;
    private Integer attemptNo;
    private String provider;
    private String providerRequestId;
    /** 提交前的管理员指定节点，与提交后的实际 nodeId 分开。 */
    private String requestedNodeId;
    private String providerTaskId;
    private String nodeId;
    private String status;
    private String lastError;
    @TableField(fill = FieldFill.INSERT, value = "create_time")
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE, value = "update_time")
    private LocalDateTime updateTime;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUBMITTING = "SUBMITTING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_SUBMIT_UNKNOWN = "SUBMIT_UNKNOWN";
    public static final String STATUS_FAILED = "FAILED";
}
