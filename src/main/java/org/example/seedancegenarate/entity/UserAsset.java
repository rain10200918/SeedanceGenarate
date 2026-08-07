package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户素材（任务提交的图片自动登记；URL 均来自本系统 OSS，已过白名单校验） */
@Data
@TableName("user_asset")
public class UserAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** IMAGE / VIDEO（预留） */
    private String type;
    /** 来源：TASK 任务提交（UPLOAD 独立上传预留） */
    private String source;
    private String url;
    /** 来源任务 video_task.task_id；独立上传时为空 */
    private String taskId;
    /** 所属文件夹；NULL=未归档 */
    private Long folderId;
    /** ACTIVE / DELETED（软删，保历史任务引用） */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
