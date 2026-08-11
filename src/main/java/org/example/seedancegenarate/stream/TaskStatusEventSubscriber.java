package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅跨实例任务状态消息，并交给本机 SSE 连接管理器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStatusEventSubscriber implements MessageListener {
    private final ObjectMapper objectMapper;
    private final TaskStreamManager taskStreamManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TaskStatusRedisMessage event = objectMapper.readValue(
                    message.getBody(), TaskStatusRedisMessage.class);
            if (event.schemaVersion() != TaskStatusRedisMessage.CURRENT_SCHEMA_VERSION
                    || event.userId() == null || event.message() == null) {
                log.warn("忽略未知任务状态 Redis 事件: schemaVersion={}", event.schemaVersion());
                return;
            }
            log.info("收到任务状态事件: taskId={}, status={}, userId={}, eventId={}",
                    event.message().taskId(), event.message().status(), event.userId(), event.eventId());
            taskStreamManager.pushLocal(event.userId(), event.message());
        } catch (Exception e) {
            log.warn("解析任务状态 Redis 事件失败: reason={}", e.getMessage());
        }
    }
}
