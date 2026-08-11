package org.example.seedancegenarate.task;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
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

/**
 * webhook 回调分发：任务终态（SUCCESS/FAILED）事务提交后，向 API Key 配置的 callbackUrl 投递一次。
 * <ul>
 *   <li>幂等：webhook_delivery 按 (taskId, status) 唯一，重复事件直接跳过；</li>
 *   <li>重试：失败按 30s / 2m / 10m 退避，最多 3 次（定时扫描 nextRetryAt 到期行）；</li>
 *   <li>签名：X-Signature = HMAC-SHA256(webhookSecret, payload)，客户端可验真、防伪造。</li>
 * </ul>
 * 回调 payload 里 video_url 是后端本地路径，客户端应凭 task_id 调 GET /api/v1/videos/{taskId}/content 取产物。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_SECONDS = {30, 120, 600};

    private final ApiCallLogMapper apiCallLogMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final WebhookDeliveryMapper webhookDeliveryMapper;
    private final ObjectMapper objectMapper;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

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
            if (key == null || !StringUtils.hasText(key.getCallbackUrl())) {
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
            try {
                webhookDeliveryMapper.insert(delivery);
            } catch (DuplicateKeyException e) {
                return; // 同任务同状态已投递过（事件重发），幂等跳过
            }
            deliver(delivery, key);
        } catch (Exception e) {
            log.warn("webhook 分发处理失败: {}", e.getMessage());
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
            log.warn("webhook 重试扫描失败: {}", e.getMessage());
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
                ApiKey key = apiKeyMapper.selectById(delivery.getApiKeyId());
                if (key == null) {
                    continue;
                }
                deliver(delivery, key);
            }
        } catch (Exception e) {
            log.warn("webhook 重试扫描失败: {}", e.getMessage());
        }
    }

    private void deliver(WebhookDelivery delivery, ApiKey key) {
        try {
            String signature = hmacSha256(key.getWebhookSecret(), delivery.getPayload());
            HttpResponse response = HttpRequest.post(key.getCallbackUrl())
                    .header("Content-Type", "application/json")
                    .header("X-Signature", signature)
                    .timeout(10_000)
                    .body(delivery.getPayload())
                    .execute();
            int attempts = delivery.getAttempts() == null ? 0 : delivery.getAttempts();
            WebhookDelivery update = new WebhookDelivery();
            update.setId(delivery.getId());
            update.setHttpCode(response.getStatus());
            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                update.setDelivered(true);
            } else {
                update.setAttempts(attempts + 1);
                long backoff = BACKOFF_SECONDS[Math.min(attempts, BACKOFF_SECONDS.length - 1)];
                update.setNextRetryAt(LocalDateTime.now().plusSeconds(backoff));
            }
            webhookDeliveryMapper.updateById(update);
        } catch (Exception e) {
            // 网络异常：记失败并排下次重试（attempts 在下次投递时递增）
            int attempts = delivery.getAttempts() == null ? 0 : delivery.getAttempts();
            long backoff = BACKOFF_SECONDS[Math.min(attempts, BACKOFF_SECONDS.length - 1)];
            WebhookDelivery update = new WebhookDelivery();
            update.setId(delivery.getId());
            update.setAttempts(attempts + 1);
            update.setNextRetryAt(LocalDateTime.now().plusSeconds(backoff));
            webhookDeliveryMapper.updateById(update);
            log.warn("webhook 投递失败(网络): task={} url={}: {}", delivery.getTaskId(), key.getCallbackUrl(), e.getMessage());
        }
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
