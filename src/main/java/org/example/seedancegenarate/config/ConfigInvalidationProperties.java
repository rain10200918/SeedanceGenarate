package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 配置失效广播频道（模型开关 / 计价改动后通知其他实例重载快照）。 */
@Data
@Component
@ConfigurationProperties(prefix = "config-invalidation")
public class ConfigInvalidationProperties {
    /** Redis 广播频道。 */
    private String channel = "local:seedance:event:config-invalidation";
}
