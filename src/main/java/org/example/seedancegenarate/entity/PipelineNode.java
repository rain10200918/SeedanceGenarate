package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 流水线节点：INPUT 素材池 / SCENE 分镜；assetIds 为 user_asset.id 的 JSON 数组字符串 */
@Data
@TableName("pipeline_node")
public class PipelineNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pipelineId;
    /** 节点顺序（0=INPUT 素材池，其后为 SCENE 分镜） */
    private Integer seq;
    /** INPUT / SCENE */
    private String kind;
    private String name;
    /** 引用 user_asset.id 数组（JSON 字符串） */
    private String assetIds;
    private String prompt;
    /** 生成时长（秒） */
    private Integer duration;
    /** 画面比例 */
    private String ratio;
    /** 分镜独立模型；空=跟随流水线模型 */
    private String model;
    /** 最近一次运行的任务；终态事件回填反查键 */
    private String taskId;
    /** 本次节点提交幂等键；重试/实例重启不能重复创建任务 */
    private String submitRequestId;
    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String status;
    /** 终态回填的生成结果（本地转存地址；刷新后仍可预览） */
    private String videoUrl;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
