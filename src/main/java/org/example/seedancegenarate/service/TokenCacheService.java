package org.example.seedancegenarate.service;

import java.time.Instant;

/** Redis Hash 保存登录 Token。 */
public interface TokenCacheService {
    boolean put(String token, Long userId, Instant expireAt, long ttlSeconds);

    CachedToken getAndRefreshIfNeeded(String token, long ttlSeconds, long refreshThresholdSeconds);

    /**
     * 删除指定 Token。Redis 成功执行 DEL 即返回 true，key 原本不存在也是成功。
     * 只有无法确认 Redis 执行成功时返回 false。
     */
    boolean delete(String token);

    record CachedToken(Long userId, Instant expireAt) {
    }
}
