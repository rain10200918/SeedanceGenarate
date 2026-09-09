package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.GenerationState;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/** 持有租约执行一次可重放的 provider 状态 GET；不保存等待中的远端生成租约。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskPollConsumer implements AsyncJobHandler {
    public static final String JOB_TYPE = "TASK_POLL";
    private static final long LEASE_SECONDS = 60;

    private final AsyncJobService asyncJobService;
    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final TaskRetryPolicy taskRetryPolicy;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${video.task-timeout-minutes:60}")
    private long timeoutMinutes;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    public static String jobKey(long videoTaskId, Long attemptId) {
        if (videoTaskId <= 0) {
            throw new IllegalArgumentException("videoTaskId must be positive");
        }
        return "task:" + videoTaskId + ":attempt:" + (attemptId == null ? "legacy" : attemptId);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public long leaseSeconds() {
        return LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        Payload payload = parse(job.getPayload());
        if (payload == null || payload.videoTaskId() == null || payload.videoTaskId() <= 0) {
            asyncJobService.complete(job);
            return;
        }
        VideoTask task = videoTaskService.getById(payload.videoTaskId());
        if (!pollable(task) || !payload.matches(task)) {
            asyncJobService.complete(job);
            return;
        }
        if (task.getNextPollAt() != null && task.getNextPollAt().isAfter(LocalDateTime.now())) {
            // 另一次 poll/回调已经把排期推到未来；这张迟到 job 只收掉。
            asyncJobService.complete(job);
            return;
        }
        String provider = StringUtils.hasText(task.getProvider()) ? task.getProvider().trim() : defaultProvider;
        VideoEngine engine;
        try {
            engine = videoEngineRegistry.get(provider);
        } catch (Exception e) {
            finishDeterministicFailure(job, task, "生成提供方配置不可用：" + safeMessage(e));
            return;
        }
        RemoteStatus remote;
        try {
            remote = engine.poll(task);
            if (remote == null || remote.getState() == null) {
                throw new IllegalStateException("供应商返回空状态");
            }
        } catch (Exception e) {
            handlePollFailure(job, task, e);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                // provider GET 在事务外；回写前用 renew UPDATE 锁住 job 行到 commit，阻断同 job 接管。
                if (!asyncJobService.renew(job, LEASE_SECONDS)) {
                    return;
                }
                VideoTask current = videoTaskService.getById(task.getId());
                if (!pollable(current) || !sameExecutionIdentity(task, current)) {
                    completeOwned(job);
                    return;
                }
                if (remote.getState() == GenerationState.PROCESSING && timedOut(current)) {
                    taskRetryPolicy.retryOrFail(current, engine, "任务执行超时");
                    deferCurrentAttempt(current, 60);
                } else {
                    try {
                        videoTaskService.updateStatus(current, remote);
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    scheduleNext(current, remote, engine);
                }
                completeOwned(job);
            });
        } catch (Exception e) {
            // 已拿到远端结果后，DB/入队异常不能被解释成“供应商失败”；最后一次保留租约接管。
            retryOrRetain(job, e);
        }
    }

    private void handlePollFailure(AsyncJob job, VideoTask task, Exception cause) {
        if (timedOut(task)) {
            try {
                transactionTemplate.executeWithoutResult(ignored -> {
                    if (!asyncJobService.renew(job, LEASE_SECONDS)) {
                        return;
                    }
                    VideoTask current = videoTaskService.getById(task.getId());
                    if (!pollable(current) || !sameExecutionIdentity(task, current)) {
                        completeOwned(job);
                        return;
                    }
                    taskRetryPolicy.retryOrFail(current, "任务执行超时，且最后状态查询失败");
                    deferCurrentAttempt(current, 60);
                    completeOwned(job);
                });
            } catch (Exception policyFailure) {
                retryOrRetain(job, policyFailure);
            }
            return;
        }
        // GET 是否已在远端产生状态变化不可推断；到最后一次也只保留给过期接管，不伪造终态。
        retryOrRetain(job, cause);
    }

    private void retryOrRetain(AsyncJob job, Exception cause) {
        if (!lastAttempt(job)) {
            asyncJobService.failAndRetry(job, safeMessage(cause));
            return;
        }
        if (asyncJobService.renew(job, LEASE_SECONDS)) {
            log.warn("轮询结果不确定且重试已耗尽，保留 RUNNING 等待租约接管: jobId={}, reason={}",
                    job.getId(), safeMessage(cause));
        }
    }

    private void finishDeterministicFailure(AsyncJob job, VideoTask expectedTask, String message) {
        if (!lastAttempt(job)) {
            asyncJobService.failAndRetry(job, message);
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            if (!asyncJobService.renew(job, LEASE_SECONDS)) {
                return;
            }
            boolean transitioned = taskStatusTransitioner.markFailedIfCurrent(expectedTask, message);
            VideoTask current = taskStatusTransitioner.findById(expectedTask.getId());
            if (transitioned || current == null || isTerminal(current)
                    || !sameExecutionIdentity(expectedTask, current)) {
                completeOwned(job);
            }
        });
    }

    private void completeOwned(AsyncJob job) {
        if (!asyncJobService.complete(job)) {
            throw new IllegalStateException("任务轮询作业租约无法完成");
        }
    }

    private boolean pollable(VideoTask task) {
        return task != null && task.getId() != null
                && "PROCESSING".equals(task.getStatus())
                && (!StringUtils.hasText(task.getPhase()) || "RUNNING".equals(task.getPhase()))
                && StringUtils.hasText(task.getProviderTaskId());
    }

    private boolean timedOut(VideoTask task) {
        LocalDateTime started = task.getLastAttemptAt() != null ? task.getLastAttemptAt() : task.getCreateTime();
        return started != null && !started.isAfter(LocalDateTime.now().minusMinutes(Math.max(timeoutMinutes, 1)));
    }

    private void scheduleNext(VideoTask task, RemoteStatus status, VideoEngine engine) {
        if (status.getState() == GenerationState.FAILED) {
            return;
        }
        long delaySeconds;
        if (status.getState() == GenerationState.SUCCESS || status.getState() == GenerationState.LOST
                || !engine.needsPolling()) {
            delaySeconds = 60;
        } else {
            LocalDateTime started = task.getLastAttemptAt() != null ? task.getLastAttemptAt() : task.getCreateTime();
            long ageSeconds = started == null ? 0 : Math.max(Duration.between(started, LocalDateTime.now()).getSeconds(), 0);
            delaySeconds = ageSeconds < 30 ? 2 : ageSeconds < 300 ? 5 : 30;
        }
        deferCurrentAttempt(task, delaySeconds);
    }

    /** 只能延后这次查询所属的远程任务，迟到 poll 不得覆盖新 attempt 的排期。 */
    private void deferCurrentAttempt(VideoTask task, long delaySeconds) {
        videoTaskService.update(new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, task.getId())
                .eq(VideoTask::getStatus, "PROCESSING")
                .eq(VideoTask::getProviderTaskId, task.getProviderTaskId())
                .eq(task.getCurrentAttemptId() != null, VideoTask::getCurrentAttemptId,
                        task.getCurrentAttemptId())
                .isNull(task.getCurrentAttemptId() == null, VideoTask::getCurrentAttemptId)
                .eq(task.getPhase() != null, VideoTask::getPhase, task.getPhase())
                .isNull(task.getPhase() == null, VideoTask::getPhase)
                .eq(task.getRetryCount() != null, VideoTask::getRetryCount, task.getRetryCount())
                .isNull(task.getRetryCount() == null, VideoTask::getRetryCount)
                .set(VideoTask::getNextPollAt, LocalDateTime.now().plusSeconds(delaySeconds)));
    }

    private Payload parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            var tree = objectMapper.readTree(json);
            JsonNode id = tree.get("videoTaskId");
            Long videoTaskId = id == null || id.isNull() ? null : id.asLong();
            boolean identityPresent = tree.has("expectedAttemptId") && tree.has("expectedProviderTaskId")
                    && tree.has("expectedProvider");
            JsonNode attempt = tree.get("expectedAttemptId");
            Long expectedAttemptId = attempt == null || attempt.isNull() ? null : attempt.asLong();
            JsonNode provider = tree.get("expectedProviderTaskId");
            String expectedProviderTaskId = provider == null || provider.isNull() ? null : provider.asText();
            JsonNode providerName = tree.get("expectedProvider");
            String expectedProvider = providerName == null || providerName.isNull()
                    ? null : providerName.asText();
            return new Payload(videoTaskId, expectedAttemptId, expectedProviderTaskId,
                    expectedProvider, identityPresent);
        } catch (Exception e) {
            log.warn("解析轮询作业参数失败: payloadLength={}", json.length());
            return null;
        }
    }

    private String safeMessage(Exception e) {
        String message = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private boolean lastAttempt(AsyncJob job) {
        int attempts = job.getAttempts() == null ? 0 : Math.max(job.getAttempts(), 0);
        int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(job.getMaxAttempts(), 1);
        return attempts + 1 >= maxAttempts;
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

    record Payload(Long videoTaskId, Long expectedAttemptId,
                   String expectedProviderTaskId, String expectedProvider, boolean identityPresent) {
        private boolean matches(VideoTask task) {
            if (!identityPresent) {
                return task.getCurrentAttemptId() == null;
            }
            return Objects.equals(expectedAttemptId, task.getCurrentAttemptId())
                    && Objects.equals(expectedProviderTaskId, task.getProviderTaskId())
                    && Objects.equals(expectedProvider, task.getProvider());
        }
    }
}
