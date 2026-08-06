package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("video_task")
public class VideoTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String taskId;
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
}
