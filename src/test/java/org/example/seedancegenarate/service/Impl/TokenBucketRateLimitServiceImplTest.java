package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.service.RateLimitResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 纯单元测试：本地兼容路径、Redis Lua 返回值解析和 Redis 故障拒绝策略。
 * Redis Lua 的实际原子性由 Redis 服务端保证，集成环境应以多实例并发场景补验。
 */
class TokenBucketRateLimitServiceImplTest {

    @Test
    void usesLocalBucketWhenRedisFeatureIsDisabled() {
        TokenBucketRateLimitServiceImpl service = service(false, mock(StringRedisTemplate.class), "test:seedance:rate");
        RateLimitConfig.Bucket bucket = bucket(2, 1, 60);

        assertTrue(service.tryAcquire("generate:user:7", bucket).allowed());
        assertTrue(service.tryAcquire("generate:user:7", bucket).allowed());
        RateLimitResult rejected = service.tryAcquire("generate:user:7", bucket);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 1);
    }

    @Test
    void skipsDisabledBucketWithoutUsingRedis() {
        RecordingRedisTemplate redisTemplate = new RecordingRedisTemplate(List.of(0L, 0L, 60000L));
        TokenBucketRateLimitServiceImpl service = service(true, redisTemplate, "test:seedance:rate");
        RateLimitConfig.Bucket bucket = bucket(1, 1, 60);
        bucket.setEnabled(false);

        assertTrue(service.tryAcquire("register:ip:127.0.0.1", bucket).allowed());
        assertEquals(null, redisTemplate.lastKey);
    }

    @Test
    void usesNamespacedRedisKeyAndRoundsRetryAfterUp() {
        RecordingRedisTemplate redisTemplate = new RecordingRedisTemplate(List.of(0L, 0L, 1501L));
        TokenBucketRateLimitServiceImpl service = service(true, redisTemplate, "test:seedance:rate:");

        RateLimitResult result = service.tryAcquire("register:ip:127.0.0.1", bucket(2, 1, 60));

        assertFalse(result.allowed());
        assertEquals(2, result.retryAfterSeconds());
        assertEquals("test:seedance:rate:register:ip:127.0.0.1", redisTemplate.lastKey);
        assertEquals(List.of("2", "1", "60000", "180000"), redisTemplate.lastArguments);
    }

    @Test
    void failsClosedWhenRedisScriptCannotRun() {
        RecordingRedisTemplate redisTemplate = new RecordingRedisTemplate(new IllegalStateException("Redis unavailable"));
        TokenBucketRateLimitServiceImpl service = service(true, redisTemplate, "test:seedance:rate");

        RateLimitResult result = service.tryAcquire("api-key:1", bucket(10, 5, 60));

        assertFalse(result.allowed());
        assertEquals(1, result.retryAfterSeconds());
    }

    private TokenBucketRateLimitServiceImpl service(boolean redisEnabled,
                                                    StringRedisTemplate redisTemplate,
                                                    String keyPrefix) {
        DistributedFeatureProperties features = new DistributedFeatureProperties();
        features.setRedisRateLimit(redisEnabled);
        RateLimitConfig config = new RateLimitConfig();
        config.setRedisKeyPrefix(keyPrefix);
        return new TokenBucketRateLimitServiceImpl(redisTemplate, features, config);
    }

    private RateLimitConfig.Bucket bucket(int capacity, int refillTokens, long refillSeconds) {
        return new RateLimitConfig.Bucket(true, capacity, refillTokens, refillSeconds);
    }

    private static class RecordingRedisTemplate extends StringRedisTemplate {
        private final Object response;
        private String lastKey;
        private List<String> lastArguments;

        private RecordingRedisTemplate(Object response) {
            this.response = response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKey = keys.get(0);
            lastArguments = java.util.Arrays.stream(args).map(String::valueOf).toList();
            if (response instanceof RuntimeException exception) {
                throw exception;
            }
            return (T) response;
        }
    }
}
