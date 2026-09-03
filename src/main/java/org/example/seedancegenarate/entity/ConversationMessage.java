package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话里的一个气泡。三种：用户原话（USER/TEXT）、Agent 整理的提示词（ASSISTANT/TEXT）、生成卡片（ASSISTANT/GENERATION）。
 * 生成卡片只是 video_task 的投影：{@code taskId} 是终态事件的反查键，{@code output} 由事件回填（和 canvas_node 同一套路）。
 */
@Data
@TableName("conversation_message")
public class ConversationMessage {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_GENERATION = "GENERATION";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    /** 冗余属主：越权判定只查这一张表 */
    private Long userId;
    /** 会话内顺序号，(conversationId, seq) 唯一 */
    private Integer seq;
    private String role;
    private String kind;
    private String content;
    /** JSON：[{type, url}] */
    private String attachments;
    /** GENERATION：video_task.biz_task_id */
    private String taskId;
    /** JSON：提交快照 */
    private String genParams;
    /** GENERATION：PENDING / PROCESSING / SUCCESS / FAILED */
    private String genStatus;
    /** JSON：{mediaType, url, cost} */
    private String output;
    private String errorMsg;
    private String clientMsgId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
