package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.Impl.VideoSubmitServiceImpl;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 超时自动重试消费：领取 TASK_RETRY 作业 → 从已落库任务反推参数重提交引擎
 * （仅 ON_SUCCESS 计费引擎入队，免费重跑）→ CAS 回写新 provider_task_id。
 * <p>
 * 与 TASK_FINALIZE 同构：行级租约多实例竞争安全；失败退避重试，超限任务标 FAILED
 * （用户可重试）并产生告警指标；biz_key 幂等防重复入队。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRetryConsumer {
    /** 重提交涉及图片重传等耗时操作，租约给足余量 */
    private static final long RETRY_LEASE_SECONDS = 300;

    private final AsyncJobService asyncJobService;
    private final VideoTaskService videoTaskService;
    private final VideoSubmitServiceImpl videoSubmitService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final AsyncJobProperties properties;
    private final ObjectMapper objectMapper;

    @Value("${video.timeout-retry-max:2}")
    private int maxRetry;

    /** 低频兜底扫描（事件通知丢失时接管）；正常由 Redis 通知即时唤醒。 */
    @Scheduled(fixedDelayString = "${async-job.reconcile-interval-ms:30000}",
            initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void consumePendingRetries() {
        consumeNow();
    }

    /** 即时消费一轮（Redis 作业通知到达时调用）。 */
    public void consumeNow() {
        List<AsyncJob> jobs = asyncJobService.claimBatch(VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY,
                properties.getClaimBatchSize(), RETRY_LEASE_SECONDS);
        for (AsyncJob job : jobs) {
            consume(job);
        }
    }

    private void consume(AsyncJob job) {
        Payload payload = parse(job.getPayload());
        if (payload == null || payload.videoTaskId() == null) {
            asyncJobService.complete(job.getId(), job.getLeaseToken());
            return;
        }
        VideoTask task = videoTaskService.getById(payload.videoTaskId());
        // 防御：任务已终态（对账已超时终止 / 已成功）或重试次数已超限 → 作业使命完成，直接收掉
        if (task == null || !"PROCESSING".equals(task.getStatus())
                || (task.getRetryCount() != null && task.getRetryCount() >= maxRetry)) {
            asyncJobService.complete(job.getId(), job.getLeaseToken());
            return;
        }
        try {
            log.info("消费超时重试作业: jobId={}, videoTaskId={}", job.getId(), payload.videoTaskId());
            if (videoSubmitService.resubmit(task)) {
                asyncJobService.complete(job.getId(), job.getLeaseToken());
            } else {
                // 被其他实例抢先（CAS 失败）：作业使命已完成，收掉
                asyncJobService.complete(job.getId(), job.getLeaseToken());
            }
        } catch (Exception e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            int maxAttempts = job.getMaxAttempts() == null ? 5 : job.getMaxAttempts();
            if (attempts + 1 >= maxAttempts) {
                // 重提交重试耗尽：任务明确失败（用户可重试），不再无限提交
                log.warn("任务超时重试耗尽，任务置失败: taskId={}, reason={}",
                        payload.videoTaskId(), e.getMessage());
                taskStatusTransitioner.markTimedOut(payload.videoTaskId(),
                        "自动重试提交失败，请手动重试：" + e.getMessage());
            }
            asyncJobService.failAndRetry(job.getId(), job.getLeaseToken(), e.getMessage());
        }
    }

    private Payload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode id = json.get("videoTaskId");
            Long videoTaskId = id == null || id.isNull() ? null : id.asLong();
            return new Payload(videoTaskId);
        } catch (Exception e) {
            log.warn("解析超时重试作业参数失败: {}", payload);
            return null;
        }
    }

    private record Payload(Long videoTaskId) {
    }
}
