package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;

public interface TokenBucketRateLimitService {
    /**
     * 尝试消耗一个令牌。Redis 限流启用时，结果由 Redis Lua 脚本原子计算；
     * 未启用时保留本地令牌桶，便于单机开发与灰度回滚。
     */
    RateLimitResult tryAcquire(String key, RateLimitConfig.Bucket bucketConfig);
}
