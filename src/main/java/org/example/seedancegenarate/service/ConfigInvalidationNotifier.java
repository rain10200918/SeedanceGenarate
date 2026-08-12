package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConfigInvalidationProperties;
import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 配置失效广播：模型开关 / 计价改动后通知所有实例重载本地快照。
 * <p>
 * 与 {@link JobAvailableNotifier} 同一思路——MySQL 是真相，Redis 只是门铃。
 * 广播丢失不影响正确性：各实例保留定时兜底重载（{@code cache.config.reload-interval-ms}），
 * 最多滞后一个周期。所以这里发布失败只记 warn，不抛。
 * <p>
 * 未启用 {@code feature.redis-config-invalidation} 时不发布（单实例部署无需广播，
 * 改动方自己已经刷新了本地快照）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigInvalidationNotifier {

    /** 模型开放开关变了。 */
    public static final String TYPE_MODEL_ACCESS = "MODEL_ACCESS";
    /** 计价配置变了。 */
    public static final String TYPE_PRICING = "PRICING";

    private final StringRedisTemplate redisTemplate;
    private final ConfigInvalidationProperties properties;
    private final DistributedFeatureProperties features;
    private final ObjectMapper objectMapper;

    public void notifyChanged(String type) {
        if (!features.isRedisConfigInvalidation()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of("type", type));
            Long receivers = redisTemplate.convertAndSend(properties.getChannel(), payload);
            log.info("已发布配置失效通知: type={}, channel={}, receivers={}",
                    type, properties.getChannel(), receivers);
        } catch (Exception e) {
            // 门铃坏了不影响正确性：兜底重载会接管
            log.warn("配置失效通知发布失败（兜底重载将接管）: type={}, reason={}", type, e.getMessage());
        }
    }
}
