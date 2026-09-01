package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.service.TokenCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Redis Hash 保存 userId 和 expireAt；仅在剩余 TTL 小于阈值时续期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisTokenCacheService implements TokenCacheService {
    private static final DefaultRedisScript<List> PUT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'expireAt', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return {1}
            """, List.class);

    private static final DefaultRedisScript<List> GET_AND_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            local expireAt = redis.call('HGET', KEYS[1], 'expireAt')
            if not userId or not expireAt then
                return {0}
            end

            local ttl = redis.call('TTL', KEYS[1])
            if ttl <= 0 then
                redis.call('DEL', KEYS[1])
                return {0}
            end

            local fullTtl = tonumber(ARGV[1])
            local threshold = tonumber(ARGV[2])
            if ttl < threshold then
                local time = redis.call('TIME')
                local nowSeconds = tonumber(time[1])
                expireAt = (nowSeconds + fullTtl) * 1000
                redis.call('HSET', KEYS[1], 'expireAt', expireAt)
                redis.call('EXPIRE', KEYS[1], fullTtl)
                ttl = fullTtl
            end
            return {1, userId, expireAt, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final AuthTokenProperties properties;

    @Override
    public boolean put(String token, Long userId, Instant expireAt, long ttlSeconds) {
        if (!valid(token) || userId == null || expireAt == null || ttlSeconds <= 0) {
            return false;
        }
        try {
            List<?> result = redisTemplate.execute(
                    PUT_SCRIPT,
                    List.of(key(token)),
                    String.valueOf(userId),
                    String.valueOf(expireAt.toEpochMilli()),
                    String.valueOf(ttlSeconds)
            );
            return result != null && !result.isEmpty() && asLong(result.get(0)) == 1;
        } catch (Exception e) {
            log.warn("写入登录 Token Redis Hash 失败: reason={}", e.getMessage());
            return false;
        }
    }

    @Override
    public CachedToken getAndRefreshIfNeeded(String token, long ttlSeconds, long refreshThresholdSeconds) {
        if (!valid(token) || ttlSeconds <= 0 || refreshThresholdSeconds < 0) {
            return null;
        }
        try {
            List<?> result = redisTemplate.execute(
                    GET_AND_REFRESH_SCRIPT,
                    List.of(key(token)),
                    String.valueOf(ttlSeconds),
                    String.valueOf(refreshThresholdSeconds)
            );
            if (result == null || result.size() < 3 || asLong(result.get(0)) != 1) {
                return null;
            }
            Long userId = asLong(result.get(1));
            Long expireAt = asLong(result.get(2));
            return userId == null || expireAt == null
                    ? null
                    : new CachedToken(userId, Instant.ofEpochMilli(expireAt));
        } catch (Exception e) {
            log.warn("读取或续期登录 Token Redis Hash 失败: reason={}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean delete(String token) {
        if (!valid(token)) {
            return true;
        }
        try {
            redisTemplate.delete(key(token));
            // DEL 返回 false 仅表示 key 原本不存在，命令本身已成功执行。
            return true;
        } catch (Exception e) {
            log.warn("删除登录 Token Redis Hash 失败: reason={}", e.getMessage());
            return true;
        }
    }

    private String key(String token) {
        String prefix = properties.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "local:seedance:auth:token";
        }
        return prefix.replaceAll(":+$", "") + ":" + token;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean valid(String token) {
        return token != null && !token.isBlank();
    }
}
