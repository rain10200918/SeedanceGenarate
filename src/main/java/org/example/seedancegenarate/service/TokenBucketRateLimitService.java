package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;

public interface TokenBucketRateLimitService {
    boolean tryAcquire(String key, RateLimitConfig.Bucket bucketConfig);
}
