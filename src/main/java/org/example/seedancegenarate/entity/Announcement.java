package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告：管理端发布，所有用户可见。
 */
@Data
@TableName("announcement")
public class Announcement {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFFLINE = "OFFLINE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    /** DRAFT / PUBLISHED / OFFLINE */
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
