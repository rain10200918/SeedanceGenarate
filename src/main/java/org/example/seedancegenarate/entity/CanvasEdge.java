package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布连线：上游节点的产物接入下游节点的某个输入端口。
 * 端点用 {@code node_key} 而非主键引用 —— 增量保存按 key upsert，用主键会在节点重建后悬空。
 */
@Data
@TableName("canvas_edge")
public class CanvasEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long canvasId;
    private String edgeKey;
    private String fromNodeKey;
    /** 上游输出端口（当前每节点单输出，固定 out；预留多输出） */
    private String fromPort;
    private String toNodeKey;
    /** 下游输入端口：prompt / image / video / audio */
    private String toPort;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
