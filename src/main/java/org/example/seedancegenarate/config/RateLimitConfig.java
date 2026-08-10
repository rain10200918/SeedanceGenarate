package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitConfig {
    private Bucket generateUser = new Bucket(true, 3, 1, 60L);
    private Bucket generateAdmin = new Bucket(true, 30, 10, 60L);
    private Bucket generateIp = new Bucket(true, 5, 1, 60L);
    private Bucket registerIp = new Bucket(true, 2, 1, 3600L);
    private Bucket promptOptimizeUser = new Bucket(true, 20, 5, 60L);
    private Bucket promptOptimizeIp = new Bucket(true, 30, 10, 60L);
    /** 对外 API：按钥匙限流（每次提交消耗 1 令牌） */
    private Bucket apiKey = new Bucket(true, 10, 5, 60L);
    /** Redis Key 前缀；生产、预发和本地共用 Redis 时必须配置为不同值。 */
    private String redisKeyPrefix = "local:seedance:rate";

    @Data
    public static class Bucket {
        private Boolean enabled;
        private Integer capacity;
        private Integer refillTokens;
        private Long refillSeconds;

        public Bucket() {
        }

        public Bucket(Boolean enabled, Integer capacity, Integer refillTokens, Long refillSeconds) {
            this.enabled = enabled;
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillSeconds = refillSeconds;
        }
    }
}
