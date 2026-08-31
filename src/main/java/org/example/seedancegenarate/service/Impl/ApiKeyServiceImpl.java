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
        return createOwned(userId, name, callbackUrl, null, null);
    }

    @Override
    public CreatedApiKey createOwned(Long userId, String name, String callbackUrl,
                                     Long createdBy, String createdIp) {
        String plainKey = "sk-" + randomHex(32);
        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setName(sanitizeName(name));
        key.setKeyPrefix(plainKey.substring(0, 10)); // sk- + 前 8 位
        key.setKeyHash(sha256Hex(plainKey));
        key.setStatus("ENABLED");
        key.setCallbackUrl(StringUtils.hasText(callbackUrl) ? callbackUrl.trim() : null);
        key.setWebhookSecret(randomHex(16));
        key.setCreatedBy(createdBy);
        key.setCreatedIp(createdIp);
        save(key);
        return new CreatedApiKey(plainKey, key);
    }

    /**
     * 自助端的列表与计数都<b>只看在用的（ENABLED）</b>。
     * <p>
     * 用户点「删除」后这把 key 就该从他眼前消失、也不该再占名额；
     * 但行本身必须留着——{@code api_call_log.api_key_id} / {@code video_task.api_key_id}
     * 还指着它，删了行就再也答不出「这笔消费是哪把 key 花的」。企业按部门归因、
     * 账单争议时要的正是这个。管理端 {@code listAll()} 仍看得到全部。
     */
    @Override
    public List<ApiKey> listByOwner(Long userId) {
        return list(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getUserId, userId)
                .eq(ApiKey::getStatus, "ENABLED")
                .orderByDesc(ApiKey::getId));
    }

    @Override
    public long countByOwner(Long userId) {
        return count(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getUserId, userId)
                .eq(ApiKey::getStatus, "ENABLED"));
    }

    @Override
    public boolean renameOwned(Long id, Long userId, String name) {
        // 归属写进 WHERE：不属于该账号时更新 0 行，不需要（也不该）先查一次
        return update(Wrappers.<ApiKey>lambdaUpdate()
                .eq(ApiKey::getId, id)
                .eq(ApiKey::getUserId, userId)
                .set(ApiKey::getName, sanitizeName(name)));
    }

    @Override
    public boolean revokeOwned(Long id, Long userId) {
        // 同上。刻意不加 eq(status,'ENABLED')：DELETE 语义上应当幂等，
        // 重复删除（并发双击、客户端重试）不该报错。
        return update(Wrappers.<ApiKey>lambdaUpdate()
                .eq(ApiKey::getId, id)
                .eq(ApiKey::getUserId, userId)
                .set(ApiKey::getStatus, "DISABLED"));
    }

    @Override
    public boolean setShareOwned(Long id, Long userId, Integer maxConcurrency) {
        // 同 renameOwned：归属写进 WHERE。null 用 set 显式写入 —— updateById 会跳过 null，
        // 那样「取消分配」这个动作永远执行不了，用户以为清了其实没清。
        return update(Wrappers.<ApiKey>lambdaUpdate()
                .eq(ApiKey::getId, id)
                .eq(ApiKey::getUserId, userId)
                .set(ApiKey::getMaxConcurrency, maxConcurrency));
    }

    @Override
    public int allocatedShare(Long userId) {
        int sum = 0;
        for (ApiKey key : list(Wrappers.<ApiKey>lambdaQuery()
                .select(ApiKey::getMaxConcurrency)
                .eq(ApiKey::getUserId, userId)
                .eq(ApiKey::getStatus, "ENABLED")
                .isNotNull(ApiKey::getMaxConcurrency))) {
            sum += key.getMaxConcurrency();
        }
        return sum;
    }

    /**
     * 备注净化：去控制字符、截断到列宽（VARCHAR(64)）。
     * 空值返回 null——由调用方决定要不要给默认名，service 不替业务做决定。
     */
    private static String sanitizeName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
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
