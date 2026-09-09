package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.Impl.VideoSubmitServiceImpl;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Objects;


/**
 * 超时自动重试消费：领取 TASK_RETRY 作业 → 原子 stage 下一 generation attempt
 * 与 GENERATION_SUBMIT job（仅引擎声明支持时入队，免费重跑）。供应商 HTTP 不在这里发生。
 * <p>
 * 与 TASK_FINALIZE 同构：行级租约多实例竞争安全；失败退避重试，超限任务标 FAILED
 * （用户可重试）并产生告警指标；biz_key 幂等防重复入队。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRetryConsumer implements AsyncJobHandler {
    /** 重提交涉及图片重传等耗时操作，租约给足余量 */
    private static final long RETRY_LEASE_SECONDS = 300;

    private final AsyncJobService asyncJobService;
    private final VideoTaskService videoTaskService;
    private final VideoSubmitServiceImpl videoSubmitService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${video.timeout-retry-max:2}")
    private int maxRetry;

    @Override
    public String jobType() {
        return VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY;
    }

    @Override
    public long leaseSeconds() {
        return RETRY_LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        Payload payload = parse(job.getPayload());
        if (payload == null || payload.videoTaskId() == null) {
            asyncJobService.complete(job);
            return;
        }
        VideoTask task = videoTaskService.getById(payload.videoTaskId());
        int currentRetry = task == null || task.getRetryCount() == null ? 0 : task.getRetryCount();
        // 防御：任务已终态（对账已超时终止 / 已成功）或重试次数已超限 → 作业使命完成，直接收掉
        if (task == null || !"PROCESSING".equals(task.getStatus())
                || "RECOVERY_REQUIRED".equals(task.getPhase())
                || currentRetry >= maxRetry
                || (payload.expectedRetryCount() != null
                    && payload.expectedRetryCount() != currentRetry)
                || !payload.matches(task)) {
            asyncJobService.complete(job);
            return;
        }
        // resubmit 会在事务回调里原地改 task；若后续 SQL 抛错，数据库能回滚，Java 对象不能。
        // exhaustion 只能用这份不可变的执行身份快照，绝不能拿“回滚过但已 mutate”的对象裁决。
        VideoTask expectedTask = executionSnapshot(task);
        try {
            log.info("消费超时重试作业: jobId={}, videoTaskId={}", job.getId(), payload.videoTaskId());
            transactionTemplate.executeWithoutResult(ignored -> {
                if (!asyncJobService.renew(job, RETRY_LEASE_SECONDS)) {
                    return;
                }
                try {
                    videoSubmitService.resubmit(task);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                // 成功或业务 CAS 已被同轮其他实例抢先，当前 job 都已完成使命。
                completeOwned(job);
            });
        } catch (Exception e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(job.getMaxAttempts(), 1);
            if (attempts + 1 < maxAttempts) {
                asyncJobService.failAndRetry(job, e.getMessage());
                return;
            }
            finishExhausted(job, expectedTask, e);
        }
    }

    private void finishExhausted(AsyncJob job, VideoTask expectedTask, Exception cause) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                if (!asyncJobService.renew(job, RETRY_LEASE_SECONDS)) {
                    return;
                }
                log.warn("任务超时重试耗尽，任务置失败: taskId={}, reason={}",
                        expectedTask.getId(), cause.getMessage());
                boolean transitioned = taskStatusTransitioner.markTimedOutIfCurrent(expectedTask,
                        "自动重试提交失败，请手动重试：" + cause.getMessage());
                VideoTask current = taskStatusTransitioner.findById(expectedTask.getId());
                if (transitioned || current == null || isTerminal(current)
                        || !sameExecutionIdentity(expectedTask, current)) {
                    completeOwned(job);
                }
            });
        } catch (Exception e) {
            log.warn("自动重试耗尽收口未完成，等待租约接管: taskId={}, reason={}",
                    expectedTask.getId(), e.getMessage());
        }
    }

    private void completeOwned(AsyncJob job) {
        if (!asyncJobService.complete(job)) {
            throw new IllegalStateException("任务重试作业租约无法完成");
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
            JsonNode expected = json.get("expectedRetryCount");
            Integer expectedRetryCount = expected == null || expected.isNull() ? null : expected.asInt();
            boolean identityPresent = json.has("expectedAttemptId")
                    && json.has("expectedProviderTaskId") && json.has("expectedPhase")
                    && json.has("expectedProvider");
            Long expectedAttemptId = nullableLong(json.get("expectedAttemptId"));
            String expectedProviderTaskId = nullableText(json.get("expectedProviderTaskId"));
            String expectedPhase = nullableText(json.get("expectedPhase"));
            String expectedProvider = nullableText(json.get("expectedProvider"));
            return new Payload(videoTaskId, expectedRetryCount, expectedAttemptId,
                    expectedProviderTaskId, expectedPhase, expectedProvider, identityPresent);
        } catch (Exception e) {
            log.warn("解析超时重试作业参数失败: payloadLength={}", payload.length());
            return null;
        }
    }

    private static Long nullableLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean isTerminal(VideoTask task) {
        return "FAILED".equals(task.getStatus()) || "SUCCESS".equals(task.getStatus());
    }

    private static boolean sameExecutionIdentity(VideoTask expected, VideoTask actual) {
        return Objects.equals(expected.getCurrentAttemptId(), actual.getCurrentAttemptId())
                && Objects.equals(expected.getProviderTaskId(), actual.getProviderTaskId())
                && Objects.equals(expected.getPhase(), actual.getPhase())
                && Objects.equals(expected.getRetryCount(), actual.getRetryCount())
                && Objects.equals(expected.getProvider(), actual.getProvider());
    }

    private static VideoTask executionSnapshot(VideoTask source) {
        VideoTask snapshot = new VideoTask();
        snapshot.setId(source.getId());
        snapshot.setStatus(source.getStatus());
        snapshot.setCurrentAttemptId(source.getCurrentAttemptId());
        snapshot.setProviderTaskId(source.getProviderTaskId());
        snapshot.setPhase(source.getPhase());
        snapshot.setRetryCount(source.getRetryCount());
        snapshot.setProvider(source.getProvider());
        return snapshot;
    }

    private record Payload(Long videoTaskId, Integer expectedRetryCount,
                           Long expectedAttemptId, String expectedProviderTaskId,
                           String expectedPhase, String expectedProvider, boolean identityPresent) {
        private boolean matches(VideoTask task) {
            if (!identityPresent) {
                // 升级前的旧 job 只允许处理同为 legacy 的任务；新 attempt 不继承旧 job 的权力。
                return task.getCurrentAttemptId() == null;
            }
            return Objects.equals(expectedAttemptId, task.getCurrentAttemptId())
                    && Objects.equals(expectedProviderTaskId, task.getProviderTaskId())
                    && Objects.equals(expectedPhase, task.getPhase())
                    && Objects.equals(expectedProvider, task.getProvider());
        }
    }
}
