package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.AsyncJobProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobAvailableNotifierTest {

    @Test
    void publishesJobTypeToConfiguredChannel() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setChannel("test:job-available");
        JobAvailableNotifier notifier = new JobAvailableNotifier(redis, properties);

        notifier.notify("TASK_FINALIZE");

        verify(redis).convertAndSend(eq("test:job-available"), contains("TASK_FINALIZE"));
    }

    @Test
    void swallowsRedisFailureSinceFallbackScanCoversIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis down"))
                .when(redis).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        JobAvailableNotifier notifier = new JobAvailableNotifier(redis, new AsyncJobProperties());

        assertDoesNotThrow(() -> notifier.notify("TASK_FINALIZE"));
    }
}
