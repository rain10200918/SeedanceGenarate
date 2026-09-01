package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.service.TokenCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTokenCacheServiceTest {

    @Test
    void storesRedisHashWithConfiguredTtl() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate(List.of(1L));
        RedisTokenCacheService service = new RedisTokenCacheService(redis, properties());
        Instant expireAt = Instant.parse("2026-09-10T00:00:00Z");

        assertTrue(service.put("abc", 42L, expireAt, 3600));
        assertEquals("test:auth:abc", redis.lastKey);
        assertEquals(List.of("42", String.valueOf(expireAt.toEpochMilli()), "3600"), redis.lastArguments);
    }

    @Test
    void parsesTokenReturnedByValidationAndRefreshScript() {
        Instant expireAt = Instant.parse("2026-09-10T00:00:00Z");
        RecordingRedisTemplate redis = new RecordingRedisTemplate(
                List.of(1L, 42L, expireAt.toEpochMilli(), 3600L));
        RedisTokenCacheService service = new RedisTokenCacheService(redis, properties());

        TokenCacheService.CachedToken cached = service.getAndRefreshIfNeeded("abc", 3600, 300);

        assertNotNull(cached);
        assertEquals(42L, cached.userId());
        assertEquals(expireAt, cached.expireAt());
        assertEquals(List.of("3600", "300"), redis.lastArguments);
    }

    @Test
    void rejectsMissingTokenResult() {
        RedisTokenCacheService service = new RedisTokenCacheService(
                new RecordingRedisTemplate(List.of(0L)), properties());

        assertEquals(null, service.getAndRefreshIfNeeded("abc", 3600, 300));
        assertFalse(service.put("", 42L, Instant.now(), 3600));
    }

    @Test
    void deletingAnAlreadyMissingKeyIsStillACompletedLogout() {
        // 【测什么】Redis DEL 返回 false（key 不存在）仍是命令成功，重复登出保持幂等。
        // 【怎么算红】把 RedisTokenCacheService.delete 直接返回 redisTemplate.delete 的布尔值，这条必须变红。
        RedisTokenCacheService service = new RedisTokenCacheService(
                new DeleteRedisTemplate(false, null), properties());

        assertTrue(service.delete("already-gone"));
    }

    @Test
    void redisDeleteFailureIsReportedToTheCaller() {
        // 【测什么】Redis DEL 连接异常向上层返回失败，不伪装成已撤销。
        // 【怎么算红】恢复 catch 后无条件返回成功，这条必须变红。
        RedisTokenCacheService service = new RedisTokenCacheService(
                new DeleteRedisTemplate(null, new IllegalStateException("redis unavailable")),
                properties());

        assertFalse(service.delete("token"));
    }

    private AuthTokenProperties properties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setKeyPrefix("test:auth");
        return properties;
    }

    private static class RecordingRedisTemplate extends StringRedisTemplate {
        private final Object result;
        private String lastKey;
        private List<String> lastArguments;

        private RecordingRedisTemplate(Object result) {
            this.result = result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKey = keys.get(0);
            lastArguments = Arrays.stream(args).map(String::valueOf).toList();
            return (T) result;
        }
    }

    private static final class DeleteRedisTemplate extends StringRedisTemplate {
        private final Boolean result;
        private final RuntimeException failure;

        private DeleteRedisTemplate(Boolean result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public Boolean delete(String key) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
