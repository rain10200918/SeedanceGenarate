package org.example.seedancegenarate.dto;

import java.time.LocalDateTime;

/**
 * API Key 管理端视图：不含 keyHash / webhookSecret 等敏感字段（明文只在创建时返回一次）；
 * username 为属主用户名（列表连表补查，便于识别）。
 */
public record ApiKeyView(
        Long id,
        Long userId,
        String username,
        String name,
        String keyPrefix,
        String status,
        LocalDateTime expiresAt,
        String callbackUrl,
        LocalDateTime lastUsedAt,
        /** 分配给这把 key 的同时可跑任务数；null = 与其他 key 共用账号总量 */
        Integer maxConcurrency,
        LocalDateTime createTime
) {
    /** 从实体裁剪敏感字段；username 由调用方传入（批量补查避免 N+1） */
    public static ApiKeyView of(org.example.seedancegenarate.entity.ApiKey key, String username) {
        return new ApiKeyView(key.getId(), key.getUserId(), username, key.getName(), key.getKeyPrefix(),
                key.getStatus(), key.getExpiresAt(), key.getCallbackUrl(),
                key.getLastUsedAt(), key.getMaxConcurrency(), key.getCreateTime());
    }
}
