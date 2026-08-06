package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenBucketRateLimitServiceImpl implements TokenBucketRateLimitService {
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, RateLimitConfig.Bucket bucketConfig) {
        if (bucketConfig == null || Boolean.FALSE.equals(bucketConfig.getEnabled())) {
            return true;
        }
        TokenBucketConfig config = new TokenBucketConfig(
                bucketConfig.getCapacity(),
                bucketConfig.getRefillTokens(),
                bucketConfig.getRefillSeconds() * 1000L
        );
        TokenBucket bucket = buckets.computeIfAbsent(
                key,
                ignored -> new TokenBucket(config.capacity())
        );
        return bucket.tryAcquire(config);
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

        private synchronized boolean tryAcquire(TokenBucketConfig config) {
            refill(config);
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        private void refill(TokenBucketConfig config) {
            long now = System.currentTimeMillis();
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
