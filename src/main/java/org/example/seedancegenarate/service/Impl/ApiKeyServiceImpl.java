package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.service.ApiKeyService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKey> implements ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public CreatedApiKey create(Long userId, String name, String callbackUrl) {
        String plainKey = "sk-" + randomHex(32);
        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setName(StringUtils.hasText(name) ? name.trim() : null);
        key.setKeyPrefix(plainKey.substring(0, 10)); // sk- + 前 8 位
        key.setKeyHash(sha256Hex(plainKey));
        key.setStatus("ENABLED");
        key.setCallbackUrl(StringUtils.hasText(callbackUrl) ? callbackUrl.trim() : null);
        key.setWebhookSecret(randomHex(16));
        save(key);
        return new CreatedApiKey(plainKey, key);
    }

    @Override
    public void revoke(Long id) {
        ApiKey key = getById(id);
        if (key == null) {
            // 管理端入口：沿用 Result 错误契约（不走 /api/v1 的 ApiException）
            throw new RuntimeException("API Key 不存在");
        }
        key.setStatus("DISABLED");
        updateById(key);
    }

    @Override
    public List<ApiKey> listAll() {
        return list(Wrappers.<ApiKey>lambdaQuery().orderByDesc(ApiKey::getId));
    }

    @Override
    public ApiKey resolveAndValidate(String plainKey) {
        if (!StringUtils.hasText(plainKey)) {
            throw ApiException.invalidApiKey();
        }
        ApiKey key = getOne(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getKeyHash, sha256Hex(plainKey.trim())), false);
        if (key == null) {
            throw ApiException.invalidApiKey();
        }
        if (!"ENABLED".equals(key.getStatus())) {
            throw ApiException.apiKeyDisabled();
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.apiKeyExpired();
        }
        return key;
    }

    @Override
    public void markUsed(ApiKey key) {
        ApiKey update = new ApiKey();
        update.setId(key.getId());
        update.setLastUsedAt(LocalDateTime.now());
        updateById(update);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
