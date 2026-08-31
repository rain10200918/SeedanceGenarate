package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不可覆盖的内容审核动作；当前状态快照在 video_task，完整历史在这里。 */
@Data
@TableName("content_moderation_action")
public class ContentModerationAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoTaskId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String reasonCode;
    private String userMessage;
    private String internalNote;
    private Long operatorId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
