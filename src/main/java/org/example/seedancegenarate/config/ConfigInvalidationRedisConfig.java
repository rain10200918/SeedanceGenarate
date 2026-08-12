package org.example.seedancegenarate.config;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.stream.ConfigInvalidationSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置失效订阅。连接失败由容器记录并重试，
 * 不能阻断配置读取——各实例仍有定时兜底重载，且手上那份旧快照仍可用。
 */
@Configuration
@ConditionalOnProperty(name = "feature.redis-config-invalidation", havingValue = "true")
@RequiredArgsConstructor
public class ConfigInvalidationRedisConfig {
    private final ConfigInvalidationProperties properties;
    private final ConfigInvalidationSubscriber subscriber;

    @Bean
    public RedisMessageListenerContainer configInvalidationRedisListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getChannel()));
        return container;
    }
}
