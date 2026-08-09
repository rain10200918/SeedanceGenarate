package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("video_task")
public class VideoTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /**
     * 兼容字段：新任务与 bizTaskId 保持相同，保证既有 UI/API/SSE 的 taskId 契约不变；
     * 历史任务保留原提供方 ID，直到完成后续全量接口迁移。
     */
    private String taskId;
    /** 系统公开任务 ID；创建任务时即生成，供后续异步提交 / Worker 阶段作为权威 ID。 */
    private String bizTaskId;
    /** 提供方任务 ID；调用引擎提交成功后回写，轮询时必须使用此字段。 */
    @JsonIgnore
    private String providerTaskId;
    private String prompt;
    private String images;
    private Integer duration;
    private String ratio;
    private String status;
    private String videoUrl;
    private String errorMsg;
    private BigDecimal costAmount;
    /** 生成提供方 */
    private String provider;
    /** 处理该任务的 ComfyUI 节点 ID */
    private String nodeId;
    /** 对外 API 来源判别列（api_key.id）；空 = UI 提交 */
    private Long apiKeyId;
    /** 模型 / 工作流标识 */
    private String model;
    /** 产物类型（VIDEO / IMAGE），提交时按模型能力定死，是任务类型的权威输出维度 */
    private String outputType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 对外 / 领域事件使用的稳定任务 ID；旧数据回退到历史 task_id。 */
    public String businessTaskId() {
        return bizTaskId == null || bizTaskId.isBlank() ? taskId : bizTaskId;
    }

    /** 调用 Seedance / ComfyUI 时使用的提供方任务 ID；旧数据回退到历史 task_id。 */
    public String remoteTaskId() {
        return providerTaskId == null || providerTaskId.isBlank() ? taskId : providerTaskId;
    }
}
