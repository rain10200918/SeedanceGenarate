package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.TaskEventProperties;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskStatusEventPublisherTest {

    @Test
    void publishesVersionedLightweightTaskEvent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(eq("test:task-status"), contains("tsk_1"))).thenReturn(1L);
        ObjectMapper mapper = new ObjectMapper();
        TaskEventProperties properties = new TaskEventProperties();
        properties.setChannel("test:task-status");
        TaskStatusEventPublisher publisher = new TaskStatusEventPublisher(redis, mapper, properties);

        boolean published = publisher.publish(new TaskStatusChangedEvent(42L,
                new TaskStatusChangedEvent.Message("tsk_1", "SUCCESS", "tsk_1.mp4",
                        "VIDEO", null, BigDecimal.ONE)));

        assertTrue(published);
        verify(redis).convertAndSend(eq("test:task-status"),
                contains("\"schemaVersion\":1"));
    }

    @Test
    void returnsFalseWhenRedisPublishFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(eq("test:task-status"), contains("tsk_1")))
                .thenThrow(new IllegalStateException("Redis down"));
        TaskEventProperties properties = new TaskEventProperties();
        properties.setChannel("test:task-status");
        TaskStatusEventPublisher publisher = new TaskStatusEventPublisher(redis, new ObjectMapper(), properties);

        assertTrue(!publisher.publish(new TaskStatusChangedEvent(42L,
                new TaskStatusChangedEvent.Message("tsk_1", "SUCCESS", null,
                        "VIDEO", null, null))));
    }
}
