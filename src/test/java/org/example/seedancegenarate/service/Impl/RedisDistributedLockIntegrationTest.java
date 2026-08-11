package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.DistributedLockProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选本地 Redis 集成测试，默认跳过；验证两个实例竞争同一把锁只有一个成功。 */
class RedisDistributedLockIntegrationTest {

    @Test
    void onlyOneInstanceHoldsTheLockUntilRelease() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_REDIS_INTEGRATION_TESTS")));
        String password = System.getenv("SPRING_REDIS_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank());

        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.setPassword(password);
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate first = new StringRedisTemplate(factory);
            StringRedisTemplate second = new StringRedisTemplate(factory);
            first.afterPropertiesSet();
            second.afterPropertiesSet();

            DistributedLockProperties properties = new DistributedLockProperties();
            properties.setEnabled(true);
            properties.setKeyPrefix("test:seedance:lock:" + UUID.randomUUID());
            RedisDistributedLock lockA = new RedisDistributedLock(first, properties);
            RedisDistributedLock lockB = new RedisDistributedLock(second, properties);

            AutoCloseable handleA = lockA.tryLock("video-poller", Duration.ofSeconds(60));
            assertNotNull(handleA);
            assertNull(lockB.tryLock("video-poller", Duration.ofSeconds(60)),
                    "第二个实例不能拿到同一把锁");

            try {
                handleA.close();
            } catch (Exception ignored) {
            }
            AutoCloseable handleB = lockB.tryLock("video-poller", Duration.ofSeconds(60));
            assertNotNull(handleB, "释放后第二个实例可以拿到锁");
            try {
                handleB.close();
            } catch (Exception ignored) {
            }
            assertTrue(true);
        } finally {
            factory.destroy();
        }
    }
}
