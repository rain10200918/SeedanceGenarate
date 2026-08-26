package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布节点。{@code nodeType} 是 {@code CanvasNodeType} 注册表的 key，
 * {@code config} 由对应实现自己解释 —— 新增节点类型不改表、不改本类。
 */
@Data
@TableName("canvas_node")
public class CanvasNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long canvasId;
    /** 稳定身份（客户端 UUID）：连线与幂等键都依赖它，跨保存不变 */
    private String nodeKey;
    /** 注册表 key：ASSET / TEXT / GENERATE */
    private String nodeType;
    private String title;
    private Integer posX;
    private Integer posY;
    private Integer width;
    private Integer height;
    /** 该类型自己的参数（JSON 字符串） */
    private String config;
    /** IDLE/PENDING/PROCESSING/SUCCESS/FAILED/BLOCKED */
    private String status;
    /** 生成节点关联的任务（终态事件反查键） */
    private String taskId;
    /** 本次提交幂等键 */
    private String submitRequestId;
    /** 产物 {mediaType,url}（JSON 字符串）；形状对所有类型统一 */
    private String output;
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
