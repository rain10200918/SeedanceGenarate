package org.example.seedancegenarate.service;

import java.time.Instant;

/** Redis Hash 保存登录 Token。 */
public interface TokenCacheService {
    boolean put(String token, Long userId, Instant expireAt, long ttlSeconds);

    CachedToken getAndRefreshIfNeeded(String token, long ttlSeconds, long refreshThresholdSeconds);

    void delete(String token);

    record CachedToken(Long userId, Instant expireAt) {
    }
}
