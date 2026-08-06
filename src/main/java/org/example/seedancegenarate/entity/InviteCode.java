package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("invite_code")
public class InviteCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String status;
    private Long createdBy;
    private Long usedBy;
    private LocalDateTime usedTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
