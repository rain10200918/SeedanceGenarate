package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.DistributedLockProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDistributedLockTest {

    @Test
    void acquiresLockWhenRedisAllows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        RedisDistributedLock lock = new RedisDistributedLock(redis, properties());

        AutoCloseable handle = lock.tryLock("video-poller", Duration.ofSeconds(300));

        assertTrue(handle != null);
        verify(valueOps).setIfAbsent(eq("test:seedance:lock:video-poller"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void returnsNullWhenLockIsHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        RedisDistributedLock lock = new RedisDistributedLock(redis, properties());

        assertNull(lock.tryLock("video-poller", Duration.ofSeconds(300)));
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("Redis down"));
        RedisDistributedLock lock = new RedisDistributedLock(redis, properties());

        assertNull(lock.tryLock("video-poller", Duration.ofSeconds(300)));
    }

    @Test
    void disabledLockNeverAcquires() {
        DistributedLockProperties properties = properties();
        properties.setEnabled(false);
        RedisDistributedLock lock = new RedisDistributedLock(mock(StringRedisTemplate.class), properties);

        assertNull(lock.tryLock("video-poller", Duration.ofSeconds(300)));
    }

    private DistributedLockProperties properties() {
        DistributedLockProperties properties = new DistributedLockProperties();
        properties.setEnabled(true);
        properties.setKeyPrefix("test:seedance:lock");
        return properties;
    }

}
