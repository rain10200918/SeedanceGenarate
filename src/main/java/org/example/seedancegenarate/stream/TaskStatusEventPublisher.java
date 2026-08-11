package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.TaskEventProperties;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 将已提交事务后的任务状态事件发布到 Redis Pub/Sub。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStatusEventPublisher {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskEventProperties properties;

    public boolean publish(TaskStatusChangedEvent event) {
        if (event == null || event.userId() == null || event.message() == null) {
            return false;
        }
        TaskStatusRedisMessage message = new TaskStatusRedisMessage(
                TaskStatusRedisMessage.CURRENT_SCHEMA_VERSION,
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                event.userId(),
                event.message());
        try {
            Long receivers = redisTemplate.convertAndSend(
                    properties.getChannel(), objectMapper.writeValueAsString(message));
            if (receivers == null || receivers <= 0) {
                log.warn("任务状态 Redis 事件没有订阅者: taskId={}, channel={}",
                        event.message().taskId(), properties.getChannel());
                return false;
            }
            log.info("已发布任务状态事件: taskId={}, status={}, userId={}, channel={}, receivers={}",
                    event.message().taskId(), event.message().status(), event.userId(),
                    properties.getChannel(), receivers);
            return true;
        } catch (Exception e) {
            log.warn("发布任务状态 Redis 事件失败: taskId={}, reason={}",
                    event.message().taskId(), e.getMessage());
            return false;
        }
    }
}
