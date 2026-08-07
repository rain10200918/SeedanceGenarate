package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户素材文件夹（树形；parentId 为 NULL 表示根目录） */
@Data
@TableName("asset_folder")
public class AssetFolder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    /** 父文件夹；NULL=根目录 */
    private Long parentId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
