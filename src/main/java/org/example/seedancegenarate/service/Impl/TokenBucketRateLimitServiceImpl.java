package org.example.seedancegenarate.service.Impl;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流。
 * <p>
 * 灰度开关打开后由 Redis Lua 在服务端原子执行「补充、判断、扣减、过期」；
 * 关闭时维持单机开发使用的本地实现。Redis 不可用时高成本入口拒绝请求，
 * 避免多实例场景静默失去保护。
 */
@Slf4j
@Service
public class TokenBucketRateLimitServiceImpl implements TokenBucketRateLimitService {
    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refillTokens = tonumber(ARGV[2])
            local refillMillis = tonumber(ARGV[3])
            local ttlMillis = tonumber(ARGV[4])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local lastRefill = tonumber(redis.call('HGET', KEYS[1], 'last_refill'))
            if not tokens or not lastRefill then
                tokens = capacity
                lastRefill = now
            else
                local elapsed = now - lastRefill
                if elapsed >= refillMillis then
                    local periods = math.floor(elapsed / refillMillis)
                    tokens = math.min(capacity, tokens + periods * refillTokens)
                    lastRefill = lastRefill + periods * refillMillis
                end
            end

            local allowed = 0
            local retryAfterMillis = 0
            if tokens > 0 then
                tokens = tokens - 1
                allowed = 1
            else
                retryAfterMillis = refillMillis - (now - lastRefill)
                if retryAfterMillis < 1 then
                    retryAfterMillis = 1
                end
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', lastRefill)
            redis.call('PEXPIRE', KEYS[1], ttlMillis)
            return {allowed, tokens, retryAfterMillis}
            """, List.class);

    private final Map<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();
    private final StringRedisTemplate stringRedisTemplate;
    private final DistributedFeatureProperties distributedFeatureProperties;
    private final RateLimitConfig rateLimitConfig;

    public TokenBucketRateLimitServiceImpl(StringRedisTemplate stringRedisTemplate,
                                           DistributedFeatureProperties distributedFeatureProperties,
                                           RateLimitConfig rateLimitConfig) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.distributedFeatureProperties = distributedFeatureProperties;
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitConfig.Bucket bucketConfig) {
        if (!isEnabled(bucketConfig)) {
            return RateLimitResult.permitted();
        }
        validate(bucketConfig);
        if (distributedFeatureProperties.isRedisRateLimit()) {
            return tryAcquireWithRedis(key, bucketConfig);
        }
        return tryAcquireLocally(key, bucketConfig);
    }

    private RateLimitResult tryAcquireWithRedis(String key, RateLimitConfig.Bucket bucketConfig) {
        long refillMillis = bucketConfig.getRefillSeconds() * 1000L;
        long ttlMillis = ttlMillis(bucketConfig);
        try {
            List<?> result = stringRedisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(redisKey(key)),
                    String.valueOf(bucketConfig.getCapacity()),
                    String.valueOf(bucketConfig.getRefillTokens()),
                    String.valueOf(refillMillis),
                    String.valueOf(ttlMillis)
            );
            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Redis 限流脚本返回异常");
            }
            boolean allowed = asLong(result.get(0)) == 1;
            if (allowed) {
                return RateLimitResult.permitted();
            }
            return RateLimitResult.rejected((asLong(result.get(2)) + 999) / 1000);
        } catch (Exception e) {
            log.warn("Redis 限流不可用，拒绝请求: key={}, reason={}", key, e.getMessage());
            return RateLimitResult.rejected(1);
        }
    }

    private RateLimitResult tryAcquireLocally(String key, RateLimitConfig.Bucket bucketConfig) {
        TokenBucketConfig config = new TokenBucketConfig(
                bucketConfig.getCapacity(),
                bucketConfig.getRefillTokens(),
                bucketConfig.getRefillSeconds() * 1000L
        );
        TokenBucket bucket = localBuckets.computeIfAbsent(key, ignored -> new TokenBucket(config.capacity()));
        return bucket.tryAcquire(config);
    }

    private static boolean isEnabled(RateLimitConfig.Bucket bucketConfig) {
        return bucketConfig != null && !Boolean.FALSE.equals(bucketConfig.getEnabled());
    }

    private static void validate(RateLimitConfig.Bucket bucketConfig) {
        if (bucketConfig.getCapacity() == null || bucketConfig.getCapacity() <= 0
                || bucketConfig.getRefillTokens() == null || bucketConfig.getRefillTokens() <= 0
                || bucketConfig.getRefillSeconds() == null || bucketConfig.getRefillSeconds() <= 0) {
            throw new IllegalStateException("限流配置必须为正数");
        }
    }

    /** 空闲至少两个补充周期后过期，防止无界累计 IP / API Key bucket。 */
    private static long ttlMillis(RateLimitConfig.Bucket bucketConfig) {
        long refillMillis = bucketConfig.getRefillSeconds() * 1000L;
        long periodsToFill = (bucketConfig.getCapacity() + bucketConfig.getRefillTokens() - 1L)
                / bucketConfig.getRefillTokens();
        return Math.max(refillMillis, periodsToFill * refillMillis) + refillMillis;
    }

    private String redisKey(String key) {
        String prefix = rateLimitConfig.getRedisKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "local:seedance:rate";
        }
        return prefix.replaceAll(":+$", "") + ":" + key;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private record TokenBucketConfig(int capacity, int refillTokens, long refillMillis) {
    }

    private static class TokenBucket {
        private int tokens;
        private long lastRefillTime;

        private TokenBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        private synchronized RateLimitResult tryAcquire(TokenBucketConfig config) {
            long now = System.currentTimeMillis();
            refill(config, now);
            if (tokens <= 0) {
                long retryAfterMillis = Math.max(config.refillMillis() - (now - lastRefillTime), 1);
                return RateLimitResult.rejected((retryAfterMillis + 999) / 1000);
            }
            tokens--;
            return RateLimitResult.permitted();
        }

        private void refill(TokenBucketConfig config, long now) {
            long elapsed = now - lastRefillTime;
            if (elapsed < config.refillMillis()) {
                return;
            }
            long periods = elapsed / config.refillMillis();
            long refill = periods * config.refillTokens();
            tokens = (int) Math.min(config.capacity(), tokens + refill);
            lastRefillTime += periods * config.refillMillis();
        }
    }
}
