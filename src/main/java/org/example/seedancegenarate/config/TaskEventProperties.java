package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Redis 任务状态通知频道配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "task-events")
public class TaskEventProperties {
    private String channel = "local:seedance:event:task-status";
}
