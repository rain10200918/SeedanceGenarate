package org.example.seedancegenarate.config;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.stream.JobAvailableSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 作业可用通知订阅。Redis 连接异常由容器重试，兜底扫描保证不丢作业。 */
@Configuration
@RequiredArgsConstructor
public class JobAvailableRedisConfig {
    private final AsyncJobProperties properties;
    private final JobAvailableSubscriber subscriber;

    @Bean
    public RedisMessageListenerContainer jobAvailableListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getChannel()));
        return container;
    }
}
