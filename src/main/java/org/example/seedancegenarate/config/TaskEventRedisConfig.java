package org.example.seedancegenarate.config;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.stream.TaskStatusEventSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Redis Pub/Sub 任务状态订阅配置。连接失败由容器记录，不能阻断 MySQL 任务状态落库。 */
@Configuration
@ConditionalOnProperty(name = "feature.redis-task-events", havingValue = "true")
@RequiredArgsConstructor
public class TaskEventRedisConfig {
    private final TaskEventProperties properties;
    private final TaskStatusEventSubscriber subscriber;

    @Bean
    public RedisMessageListenerContainer taskEventRedisListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getChannel()));
        return container;
    }
}
