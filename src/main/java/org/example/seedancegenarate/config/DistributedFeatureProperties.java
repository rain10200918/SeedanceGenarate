package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分布式能力的灰度开关。
 * <p>
 * Redis 组件异常时可先关闭对应开关回退到单实例兼容实现；
 * 多 API 实例部署时必须开启相关分布式能力。
 */
@Data
@Component
@ConfigurationProperties(prefix = "feature")
public class DistributedFeatureProperties {
    /** 是否启用 Redis Lua 分布式限流；关闭时使用本地令牌桶兼容实现。 */
    private boolean redisRateLimit = false;
    /** 是否启用 Redis Pub/Sub 跨实例任务状态通知；关闭时只走本机 SSE。 */
    private boolean redisTaskEvents = false;
}
