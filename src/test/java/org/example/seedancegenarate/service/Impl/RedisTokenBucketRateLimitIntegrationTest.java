package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.service.RateLimitResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可选 Redis 集成测试。只有明确设置 RUN_REDIS_INTEGRATION_TESTS=true 时才连接本地 Redis，
 * 从而不使默认 Maven 测试依赖开发机或 CI 的 Redis 服务。
 */
class RedisTokenBucketRateLimitIntegrationTest {

    @Test
    void sharesOneBucketBetweenIndependentServiceInstances() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_REDIS_INTEGRATION_TESTS")));

        String password = System.getenv("SPRING_REDIS_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "RUN_REDIS_INTEGRATION_TESTS=true 时必须设置 SPRING_REDIS_PASSWORD");
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.setPassword(password);
        connectionFactory.afterPropertiesSet();
        try {
            StringRedisTemplate firstTemplate = new StringRedisTemplate(connectionFactory);
            StringRedisTemplate secondTemplate = new StringRedisTemplate(connectionFactory);
            firstTemplate.afterPropertiesSet();
            secondTemplate.afterPropertiesSet();

            String prefix = "test:seedance:rate:" + UUID.randomUUID();
            TokenBucketRateLimitServiceImpl first = service(firstTemplate, prefix);
            TokenBucketRateLimitServiceImpl second = service(secondTemplate, prefix);
            RateLimitConfig.Bucket bucket = new RateLimitConfig.Bucket(true, 2, 1, 60L);

            assertTrue(first.tryAcquire("generate:user:1", bucket).allowed());
            assertTrue(second.tryAcquire("generate:user:1", bucket).allowed());
            RateLimitResult rejected = first.tryAcquire("generate:user:1", bucket);
            assertFalse(rejected.allowed());
        } finally {
            connectionFactory.destroy();
        }
    }

    private TokenBucketRateLimitServiceImpl service(StringRedisTemplate template, String prefix) {
        DistributedFeatureProperties features = new DistributedFeatureProperties();
        features.setRedisRateLimit(true);
        RateLimitConfig config = new RateLimitConfig();
        config.setRedisKeyPrefix(prefix);
        return new TokenBucketRateLimitServiceImpl(template, features, config);
    }
}
