package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 任务终态收尾消费：领取 TASK_FINALIZE 作业 → 下载产物 → OSS → 落 SUCCESS → 计费 → 事件。
 * <p>
 * 行级租约保证多实例多个 Worker 并行处理不同任务（高并发吞吐），且同一任务只被一个
 * Worker 收尾；失败退避重试，超过上限任务标 FAILED（用户可重试），作业进 DEAD。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskFinalizeConsumer implements AsyncJobHandler {
    /** 下载大文件耗时可能较长，租约给足余量（默认 60s 不够）。 */
    private static final long FINALIZE_LEASE_SECONDS = 300;

    private final AsyncJobService asyncJobService;
    private final VideoTaskService videoTaskService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String jobType() {
        return VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE;
    }

    @Override
    public long leaseSeconds() {
        return FINALIZE_LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        Payload payload = parse(job.getPayload(), job.getId());
        if (payload == null || payload.videoTaskId() == null) {
            asyncJobService.complete(job);
            return;
        }
        VideoTask task = videoTaskService.getById(payload.videoTaskId());
        if (task == null || !payload.matches(task) || "RECOVERY_REQUIRED".equals(task.getPhase())) {
            asyncJobService.complete(job);
            return;
        }
        try {
            log.info("消费终态作业: jobId={}, videoTaskId={}", job.getId(), payload.videoTaskId());
            videoTaskService.finalizeTask(task, payload.remoteVideoUrl(), job, FINALIZE_LEASE_SECONDS);
        } catch (Exception e) {
            if (!lastAttempt(job)) {
                asyncJobService.failAndRetry(job, e.getMessage());
                return;
            }
            finishExhausted(job, task, e);
        }
    }

    private void finishExhausted(AsyncJob job, VideoTask expectedTask, Exception cause) {
        // 不先把 job 打 DEAD：任务终态落库后如崩溃，新租约会重放 finalizeTask 的终态幂等并 complete。
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                if (!asyncJobService.renew(job, FINALIZE_LEASE_SECONDS)) {
                    return;
                }
                log.warn("任务终态转存重试耗尽，任务置失败: taskId={}, reason={}",
                        expectedTask.getId(), cause.getMessage());
                boolean transitioned = taskStatusTransitioner.markFailedIfCurrent(expectedTask,
                        "产物转存失败，请重试：" + cause.getMessage());
                VideoTask current = taskStatusTransitioner.findById(expectedTask.getId());
                if (transitioned || current == null || isTerminal(current)
                        || !sameExecutionIdentity(expectedTask, current)) {
                    completeOwned(job);
                }
            });
        } catch (Exception e) {
            // 保留 RUNNING，不造 DEAD + PROCESSING；租约过期后接管者重放。
            log.warn("转存耗尽收口未完成，等待租约接管: taskId={}, reason={}",
                    expectedTask.getId(), e.getMessage());
        }
    }

    private void completeOwned(AsyncJob job) {
        if (!asyncJobService.complete(job)) {
            throw new IllegalStateException("任务终态作业租约无法完成");
        }
    }

    private boolean lastAttempt(AsyncJob job) {
        int attempts = job.getAttempts() == null ? 0 : Math.max(job.getAttempts(), 0);
        int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(job.getMaxAttempts(), 1);
        return attempts + 1 >= maxAttempts;
    }

    private Payload parse(String payload, Long jobId) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode id = json.get("videoTaskId");
            JsonNode url = json.get("remoteVideoUrl");
            Long videoTaskId = id == null || id.isNull() ? null : id.asLong();
            String remoteVideoUrl = url == null || url.isNull() ? null : url.asText();
            boolean identityPresent = json.has("expectedAttemptId") && json.has("expectedProviderTaskId")
                    && json.has("expectedProvider") && json.has("expectedPhase")
                    && json.has("expectedRetryCount");
            Long expectedAttemptId = nullableLong(json.get("expectedAttemptId"));
            String expectedProviderTaskId = nullableText(json.get("expectedProviderTaskId"));
            String expectedProvider = nullableText(json.get("expectedProvider"));
            String expectedPhase = nullableText(json.get("expectedPhase"));
            Integer expectedRetryCount = nullableInteger(json.get("expectedRetryCount"));
            return new Payload(videoTaskId, remoteVideoUrl, expectedAttemptId,
                    expectedProviderTaskId, expectedProvider, expectedPhase,
                    expectedRetryCount, identityPresent);
        } catch (Exception e) {
            // payload 含供应商签名 URL；绝不能把 token/signature 写入日志。
            log.warn("解析任务终态作业参数失败: jobId={}, payloadLength={}",
                    jobId, payload == null ? 0 : payload.length());
            return null;
        }
    }

    private static Long nullableLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
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

    private record Payload(Long videoTaskId, String remoteVideoUrl,
                           Long expectedAttemptId, String expectedProviderTaskId,
                           String expectedProvider, String expectedPhase,
                           Integer expectedRetryCount, boolean identityPresent) {
        private boolean matches(VideoTask task) {
            if (!identityPresent) {
                return task.getCurrentAttemptId() == null;
            }
            return Objects.equals(expectedAttemptId, task.getCurrentAttemptId())
                    && Objects.equals(expectedProviderTaskId, task.getProviderTaskId())
                    && Objects.equals(expectedProvider, task.getProvider())
                    && Objects.equals(expectedPhase, task.getPhase())
                    && Objects.equals(expectedRetryCount, task.getRetryCount());
        }
    }
}
