package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.service.TokenCacheService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选本地 Redis 集成测试，默认跳过。 */
class RedisTokenHashIntegrationTest {

    @Test
    void storesHashValidatesRefreshesAndDeletesToken() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_REDIS_INTEGRATION_TESTS")));
        String password = System.getenv("SPRING_REDIS_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank());

        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.setPassword(password);
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            AuthTokenProperties properties = new AuthTokenProperties();
            properties.setKeyPrefix("test:seedance:auth:" + UUID.randomUUID());
            RedisTokenCacheService service = new RedisTokenCacheService(redis, properties);

            assertTrue(service.put("token", 42L, Instant.now().plusSeconds(60), 60));
            TokenCacheService.CachedToken cached = service.getAndRefreshIfNeeded("token", 60, 300);
            assertNotNull(cached);
            assertEquals(42L, cached.userId());
            Long refreshedTtl = redis.getExpire(properties.getKeyPrefix() + ":token");
            assertNotNull(refreshedTtl);
            assertTrue(refreshedTtl > 50 && refreshedTtl <= 60);

            service.delete("token");
            assertNull(service.getAndRefreshIfNeeded("token", 60, 300));
        } finally {
            factory.destroy();
        }
    }
}
