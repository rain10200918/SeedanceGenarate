package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;

public interface TokenBucketRateLimitService {
    /**
     * 尝试消耗一个令牌。Redis 限流启用时，结果由 Redis Lua 脚本原子计算；
     * 未启用时保留本地令牌桶，便于单机开发与灰度回滚。
     */
    RateLimitResult tryAcquire(String key, RateLimitConfig.Bucket bucketConfig);

    /**
     * 强制使用共享 Redis 的安全限流入口。Redis 不可用、桶缺失或桶被关闭时抛异常，
     * 由调用方失败关闭；不受单机开发用的 redis-rate-limit 灰度开关影响。
     */
    RateLimitResult tryAcquireDistributed(String key, RateLimitConfig.Bucket bucketConfig);
}
