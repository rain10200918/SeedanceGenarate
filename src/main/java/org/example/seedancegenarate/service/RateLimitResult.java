package org.example.seedancegenarate.service;

/**
 * 一次令牌桶尝试的结果。
 *
 * @param allowed      是否获得令牌
 * @param retryAfterSeconds 被拒绝时建议等待的秒数；允许或未启用限流时为 0
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult permitted() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(retryAfterSeconds, 1));
    }
}
