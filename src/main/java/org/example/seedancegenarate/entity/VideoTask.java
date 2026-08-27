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
    /** 参考图片 OSS URL 的 JSON 数组（顺序对应 <Picture 1..N>） */
    private String images;
    /** 参考视频 OSS URL 的 JSON 数组（顺序对应 <Video 1..N>） */
    private String referenceVideos;
    /** 参考音频 OSS URL 的 JSON 数组（顺序对应 <Audio 1..N>） */
    private String referenceAudios;
    private Integer duration;
    private String ratio;
    private String status;
    /** 对前端公开的稳定媒体路由标识；旧记录为 data/videos/ 本地路径，新记录为业务 ID 文件名。 */
    private String videoUrl;
    /** 正式产物存储类型（当前为 OSS）。 */
    @JsonIgnore
    private String artifactStorageType;
    /** OSS object key；是产物的存储真相，不直接下发客户端。 */
    @JsonIgnore
    private String artifactKey;
    @JsonIgnore
    private String artifactContentType;
    @JsonIgnore
    private Long artifactSize;
    @JsonIgnore
    private String artifactEtag;
    private String errorMsg;
    /** 轮询退避：下次可查询时间；NULL=立即可查（CALLBACK 机制引擎不更新） */
    private LocalDateTime nextPollAt;
    private BigDecimal costAmount;
    /** 提交时冻结金额（预授权快照，V13 新增）：结算/解冻用它，不用实时价 */
    private BigDecimal freezeAmount;
    /** 提交时单价/币种快照（V15）；消费记录不得读取实时价格 */
    private BigDecimal freezeUnitPrice;
    private String freezeCurrency;
    /** 生成提供方 */
    private String provider;
    /** 处理该任务的 ComfyUI 节点 ID */
    private String nodeId;
    /** 超时自动重试次数（ON_SUCCESS 计费引擎免费重跑；V9 新增） */
    private Integer retryCount;
    /** 本轮尝试起点（首次=create_time，重试后=now；V9 新增） */
    private LocalDateTime lastAttemptAt;
    /** 对外 API 来源判别列（api_key.id）；空 = UI 提交 */
    private Long apiKeyId;
    /** 用户提交幂等键；V15 与 user_id 组成唯一键 */
    private String requestId;
    /** 模型 / 工作流标识 */
    private String model;
    /** 产物类型（VIDEO / IMAGE），提交时按模型能力定死，是任务类型的权威输出维度 */
    private String outputType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 产物是否已被 OSS 生命周期规则删除（派生字段，不落库）。
     * <p>
     * 由 {@link org.example.seedancegenarate.service.ArtifactExpiryPolicy} 在接口返回前打标。
     * 前端据此把封面/播放按钮渲染成「视频已过期」，不必等用户点击才发现播放器转不出来。
     * <p>
     * <b>不查询时为 null，前端按「非 true 即未过期」处理</b>——只增不改，老客户端忽略即可。
     */
    @TableField(exist = false)
    private Boolean artifactExpired;

    /** 对外 / 领域事件使用的稳定任务 ID；旧数据回退到历史 task_id。 */
    public String businessTaskId() {
        return bizTaskId == null || bizTaskId.isBlank() ? taskId : bizTaskId;
    }

    /** 调用 Seedance / ComfyUI 时使用的提供方任务 ID；旧数据回退到历史 task_id。 */
    public String remoteTaskId() {
        return providerTaskId == null || providerTaskId.isBlank() ? taskId : providerTaskId;
    }
}
