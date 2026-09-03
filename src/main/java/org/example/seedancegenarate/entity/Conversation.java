package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 对话式创作：一条对话。消息见 {@link ConversationMessage}。 */
@Data
@TableName("conversation")
public class Conversation {
    public static final String TITLE_AUTO = "AUTO";
    public static final String TITLE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** AUTO 由首条消息生成 / USER 用户改过名，之后不再自动覆盖 */
    private String titleSource;
    private LocalDateTime lastMessageAt;
    /** 已分配的 seq 上限：发一轮消息时在行锁内一次预留整轮的槽位 */
    private Integer messageCount;
    private Boolean archived;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
