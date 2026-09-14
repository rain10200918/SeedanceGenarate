package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.WebhookDelivery;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.WebhookCallbackClient;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.mapper.WebhookDeliveryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * webhook 回调分发：任务终态（SUCCESS/FAILED）事务提交后，向 API Key 配置的 callbackUrl 投递一次。
 * <ul>
 *   <li>幂等：webhook_delivery 按 (taskId, status) 唯一，重复事件直接跳过；</li>
 *   <li>重试：失败按 30s / 2m 退避，含首发最多 3 次尝试（扫描 nextRetryAt 到期行）；</li>
 *   <li>签名：X-Signature = HMAC-SHA256(webhookSecret, payload)，客户端可验真、防伪造。</li>
 * </ul>
 * 回调 payload 里 video_url 是后端本地路径，客户端应凭 task_id 调 GET /api/v1/videos/{taskId}/content 取产物。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_SECONDS = {30, 120};

    private final ApiCallLogMapper apiCallLogMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final WebhookDeliveryMapper webhookDeliveryMapper;
    private final ObjectMapper objectMapper;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;
    private final WebhookCallbackClient webhookCallbackClient;

    /** 锁 TTL：单轮最多 50 次投递 × 10s 超时可能接近 10 分钟，给足余量。 */
    private static final java.time.Duration LOCK_TTL = java.time.Duration.ofSeconds(900);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusChanged(TaskStatusChangedEvent event) {
        if (event.message() == null || event.message().taskId() == null) {
            return;
        }
        try {
            // 只处理对外 API 来源的任务（有调用日志）
            ApiCallLog callLog = apiCallLogMapper.selectOne(Wrappers.<ApiCallLog>lambdaQuery()
                    .eq(ApiCallLog::getTaskId, event.message().taskId()));
            if (callLog == null) {
                return;
            }
            ApiKey key = apiKeyMapper.selectById(callLog.getApiKeyId());
            if (!enabled(key)) {
                return; // 未配置回调，不投递
            }
            String payload = buildPayload(event);
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setTaskId(event.message().taskId());
            delivery.setApiKeyId(key.getId());
            delivery.setStatus(event.message().status());
            delivery.setPayload(payload);
            delivery.setAttempts(0);
            delivery.setDelivered(false);
            delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(30));
            try {
                webhookDeliveryMapper.insert(delivery);
            } catch (DuplicateKeyException e) {
                return; // 同任务同状态已投递过（事件重发），幂等跳过
            }
            deliver(delivery);
        } catch (Exception e) {
            log.warn("webhook 分发处理失败: type={}", e.getClass().getSimpleName());
        }
    }

    /** 定时重试：投递失败且未到上限的行，到期自动重发（同一 payload，客户端按签名去重） */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void retryPending() {
        if (!lockProperties.isEnabled()) {
            // 单实例开发：未启用锁，直接执行（兼容旧行为）
            retryPendingLocked();
            return;
        }
        // 多实例部署时同一时刻只有一个实例扫描，防止同一行被并发投递；
        // Redis 不可用时跳过本轮（fail-closed），恢复后自动继续。
        AutoCloseable lock = distributedLock.tryLock("webhook-retry", LOCK_TTL);
        if (lock == null) {
            return;
        }
        try (lock) {
            retryPendingLocked();
        } catch (Exception e) {
            log.warn("webhook 重试扫描失败: type={}", e.getClass().getSimpleName());
        }
    }

    private void retryPendingLocked() {
        try {
            List<WebhookDelivery> pending = webhookDeliveryMapper.selectList(
                    Wrappers.<WebhookDelivery>lambdaQuery()
                            .eq(WebhookDelivery::getDelivered, false)
                            .lt(WebhookDelivery::getAttempts, MAX_ATTEMPTS)
                            .le(WebhookDelivery::getNextRetryAt, LocalDateTime.now())
                            .last("limit 50"));
            for (WebhookDelivery delivery : pending) {
                deliver(delivery);
            }
        } catch (Exception e) {
            log.warn("webhook 重试扫描失败: type={}", e.getClass().getSimpleName());
        }
    }

    private void deliver(WebhookDelivery delivery) {
        Integer httpCode = null;
        try {
            ApiKey key = apiKeyMapper.selectById(delivery.getApiKeyId());
            if (!enabled(key)) {
                stop(delivery);
                return;
            }
            var target = webhookCallbackClient.validate(key.getCallbackUrl());
            // DNS can block: re-read configuration AND the delivery after it completes.
            ApiKey current = apiKeyMapper.selectById(delivery.getApiKeyId());
            if (!enabled(current)) {
                stop(delivery);
                return;
            }
            WebhookDelivery latest = webhookDeliveryMapper.selectById(delivery.getId());
            if (latest == null || Boolean.TRUE.equals(latest.getDelivered())
                    || !Objects.equals(latest.getAttempts(), delivery.getAttempts())
                    || (latest.getAttempts() != null && latest.getAttempts() >= MAX_ATTEMPTS)) return;
            if (!Objects.equals(key.getCallbackUrl(), current.getCallbackUrl())) {
                // A changed URL must get its own fresh DNS validation on the next attempt.
                recordAttempt(delivery, null);
                return;
            }
            String signature = hmacSha256(current.getWebhookSecret(), delivery.getPayload());
            httpCode = webhookCallbackClient.post(target, delivery.getPayload(), signature);
        } catch (BusinessException e) {
            if (Integer.valueOf(400).equals(e.getCode())) {
                // Unsafe legacy URLs and non-public DNS must never reach the network.
                stop(delivery);
                return;
            }
            // Temporary DNS failure (503) consumes only this attempt, with normal backoff.
        } catch (Exception e) {
            log.warn("webhook 投递失败: delivery={} type={}", delivery.getId(), e.getClass().getSimpleName());
        }
        recordAttempt(delivery, httpCode);
    }

    private static boolean enabled(ApiKey key) {
        return key != null && "ENABLED".equals(key.getStatus())
                && StringUtils.hasText(key.getCallbackUrl()) && StringUtils.hasText(key.getWebhookSecret());
    }

    private void stop(WebhookDelivery delivery) {
        webhookDeliveryMapper.update(null, pendingWrite(delivery)
                .set(WebhookDelivery::getAttempts, MAX_ATTEMPTS)
                .set(WebhookDelivery::getNextRetryAt, null));
    }

    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WebhookDelivery>
    pendingWrite(WebhookDelivery delivery) {
        var update = Wrappers.<WebhookDelivery>lambdaUpdate()
                .eq(WebhookDelivery::getId, delivery.getId())
                .eq(WebhookDelivery::getDelivered, false)
                .lt(WebhookDelivery::getAttempts, MAX_ATTEMPTS);
        if (delivery.getAttempts() == null) update.isNull(WebhookDelivery::getAttempts);
        else update.eq(WebhookDelivery::getAttempts, delivery.getAttempts());
        return update;
    }

    private void recordAttempt(WebhookDelivery delivery, Integer httpCode) {
        int attempts = delivery.getAttempts() == null ? 0 : delivery.getAttempts();
        boolean success = httpCode != null && httpCode >= 200 && httpCode < 300;
        webhookDeliveryMapper.update(null, pendingWrite(delivery)
                .set(WebhookDelivery::getHttpCode, httpCode)
                .set(WebhookDelivery::getAttempts, attempts + 1)
                .set(WebhookDelivery::getDelivered, success)
                .set(WebhookDelivery::getNextRetryAt, success || attempts + 1 >= MAX_ATTEMPTS ? null
                        : LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[attempts])));
    }

    private String buildPayload(TaskStatusChangedEvent event) {
        TaskStatusChangedEvent.Message m = event.message();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", m.taskId());
        payload.put("status", m.status());
        payload.put("output_type", m.outputType());
        payload.put("video_url", m.videoUrl());
        payload.put("error", m.errorMsg());
        payload.put("cost_amount", m.costAmount());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("webhook payload 序列化失败", e);
        }
    }

    private String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }
}
