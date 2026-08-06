package org.example.seedancegenarate.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外 API 钥匙。明文只在创建时展示一次，库里只存 SHA-256 哈希（keyHash）+ 展示前缀（keyPrefix）。
 * 绑定属主用户：API 调用产生的任务/计费都记在属主名下。
 */
@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 属主用户 */
    private Long userId;
    /** 用途备注（官网演示 / 合作方A） */
    private String name;
    /** 展示前缀（sk- 前 8 位），后台对账用 */
    private String keyPrefix;
    /** SHA-256 十六进制哈希，不存明文 */
    private String keyHash;
    /** ENABLED / DISABLED */
    private String status;
    private LocalDateTime expiresAt;
    /** webhook 回调地址 */
    private String callbackUrl;
    /** 回调 HMAC 签名密钥 */
    private String webhookSecret;
    private LocalDateTime lastUsedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
