package org.example.seedancegenarate.service.Impl;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.service.DistributedLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Redis SET NX PX 原子加锁，释放时用 Lua 校验持有者后再删除。 */
@Slf4j
@Service
public class RedisDistributedLock implements DistributedLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final DistributedLockProperties properties;

    public RedisDistributedLock(StringRedisTemplate redisTemplate,
                                DistributedLockProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public AutoCloseable tryLock(String key, Duration ttl) {
        if (!properties.isEnabled() || key == null || key.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return null;
        }
        String redisKey = redisKey(key);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return (AutoCloseable) () -> release(redisKey, token);
            }
            return null;
        } catch (Exception e) {
            log.warn("分布式锁不可用，跳过任务: key={}, reason={}", key, e.getMessage());
            return null;
        }
    }

    private void release(String redisKey, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(redisKey), token);
        } catch (Exception e) {
            log.warn("释放分布式锁失败（锁会按 TTL 自动过期）: key={}, reason={}", redisKey, e.getMessage());
        }
    }

    private String redisKey(String key) {
        String prefix = properties.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "local:seedance:lock";
        }
        return prefix.replaceAll(":+$", "") + ":" + key;
    }
}
