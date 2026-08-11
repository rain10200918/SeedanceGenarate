package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 全局定时任务分布式锁配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "distributed.lock")
public class DistributedLockProperties {
    /** 是否启用；关闭时所有实例各自执行定时任务（仅限单实例部署）。 */
    private boolean enabled = false;
    /** 与其他环境隔离的 Redis Key 前缀。 */
    private String keyPrefix = "local:seedance:lock";
    /** 锁默认 TTL（秒），超过后自动释放，避免持有实例崩溃导致永久卡死。 */
    private long defaultTtlSeconds = 120;
}
