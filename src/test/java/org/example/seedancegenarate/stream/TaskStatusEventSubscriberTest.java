package org.example.seedancegenarate.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskStatusEventSubscriberTest {

    @Test
    void forwardsValidMessageToLocalStreamManager() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskStreamManager streamManager = mock(TaskStreamManager.class);
        TaskStatusEventSubscriber subscriber = new TaskStatusEventSubscriber(mapper, streamManager);
        TaskStatusRedisMessage event = new TaskStatusRedisMessage(1, "evt_1", 42L,
                new TaskStatusChangedEvent.Message("tsk_1", "SUCCESS", "tsk_1.mp4",
                        "VIDEO", null, BigDecimal.ONE));

        subscriber.onMessage(new DefaultMessage(
                "channel".getBytes(StandardCharsets.UTF_8),
                mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)), null);

        verify(streamManager).pushLocal(42L, event.message());
    }

    @Test
    void ignoresUnsupportedSchemaVersion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskStreamManager streamManager = mock(TaskStreamManager.class);
        TaskStatusEventSubscriber subscriber = new TaskStatusEventSubscriber(mapper, streamManager);
        TaskStatusRedisMessage event = new TaskStatusRedisMessage(99, "evt_1", 42L,
                new TaskStatusChangedEvent.Message("tsk_1", "SUCCESS", null,
                        "VIDEO", null, null));

        subscriber.onMessage(new DefaultMessage(
                "channel".getBytes(StandardCharsets.UTF_8),
                mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)), null);

        org.mockito.Mockito.verifyNoInteractions(streamManager);
    }
}
