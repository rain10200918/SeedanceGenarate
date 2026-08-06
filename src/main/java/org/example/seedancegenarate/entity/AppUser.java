package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String role;
    private BigDecimal totalCost;
    private String registerIp;
    private String registerIpLocation;
    private String lastLoginIp;
    private String lastLoginIpLocation;
    private LocalDateTime lastLoginTime;
    private String lastActiveIp;
    private String lastActiveIpLocation;
    private String lastOperation;
    private LocalDateTime lastOperationTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
