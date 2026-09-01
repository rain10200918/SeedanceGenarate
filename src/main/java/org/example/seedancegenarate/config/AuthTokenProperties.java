package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 登录 Token 仅存 Redis Hash，用户资料仍以 MySQL 为准。 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.token")
public class AuthTokenProperties {
    /** 登录 Token 完整有效期（默认 3 天）。 */
    private long ttlSeconds = 3L * 24 * 60 * 60;
    /** 剩余 TTL 低于该值时，认证拦截链会将 Token 续回完整有效期。 */
    private long refreshThresholdSeconds = 5 * 60;
    /** 与其他环境隔离的 Redis Key 前缀。 */
    private String keyPrefix = "local:seedance:auth:token";
}
