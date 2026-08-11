package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 任务终态收尾消费：领取 TASK_FINALIZE 作业 → 下载产物 → OSS → 落 SUCCESS → 计费 → 事件。
 * <p>
 * 行级租约保证多实例多个 Worker 并行处理不同任务（高并发吞吐），且同一任务只被一个
 * Worker 收尾；失败退避重试，超过上限任务标 FAILED（用户可重试），作业进 DEAD。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskFinalizeConsumer {
    /** 下载大文件耗时可能较长，租约给足余量（默认 60s 不够）。 */
    private static final long FINALIZE_LEASE_SECONDS = 300;

    private final AsyncJobService asyncJobService;
    private final VideoTaskService videoTaskService;
    private final AsyncJobProperties properties;
    private final ObjectMapper objectMapper;

    /** 低频兜底扫描（事件通知丢失时接管）；正常由 Redis 通知即时唤醒。 */
    @Scheduled(fixedDelayString = "${async-job.reconcile-interval-ms:30000}",
            initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void consumePendingFinalizes() {
        consumeNow();
    }

    /** 即时消费一轮（Redis 作业通知到达时调用；行级租约保证多实例竞争安全）。 */
    public void consumeNow() {
        List<AsyncJob> jobs = asyncJobService.claimBatch(VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE,
                properties.getClaimBatchSize(), FINALIZE_LEASE_SECONDS);
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
        try {
            log.info("消费终态作业: jobId={}, videoTaskId={}", job.getId(), payload.videoTaskId());
            videoTaskService.finalizeTask(payload.videoTaskId(), payload.remoteVideoUrl());
            asyncJobService.complete(job.getId(), job.getLeaseToken());
        } catch (Exception e) {
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            int maxAttempts = job.getMaxAttempts() == null ? 5 : job.getMaxAttempts();
            if (attempts + 1 >= maxAttempts) {
                // 转存重试耗尽：任务明确失败（用户可重试），不再无限下载
                log.warn("任务终态转存重试耗尽，任务置失败: taskId={}, reason={}",
                        payload.videoTaskId(), e.getMessage());
                markTaskFailed(payload.videoTaskId(), "产物转存失败，请重试：" + e.getMessage());
            }
            asyncJobService.failAndRetry(job.getId(), job.getLeaseToken(), e.getMessage());
        }
    }

    private void markTaskFailed(Long videoTaskId, String message) {
        try {
            // CAS：只有仍 PROCESSING 的任务置失败，防止覆盖并发终态结果
            videoTaskService.update(new LambdaUpdateWrapper<VideoTask>()
                    .eq(VideoTask::getId, videoTaskId)
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .set(VideoTask::getStatus, "FAILED")
                    .set(VideoTask::getErrorMsg, message));
        } catch (Exception e) {
            log.warn("标记任务失败失败: taskId={}, reason={}", videoTaskId, e.getMessage());
        }
    }

    private Payload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode id = json.get("videoTaskId");
            JsonNode url = json.get("remoteVideoUrl");
            Long videoTaskId = id == null || id.isNull() ? null : id.asLong();
            String remoteVideoUrl = url == null || url.isNull() ? null : url.asText();
            return new Payload(videoTaskId, remoteVideoUrl);
        } catch (Exception e) {
            log.warn("解析任务终态作业参数失败: {}", payload);
            return null;
        }
    }

    private record Payload(Long videoTaskId, String remoteVideoUrl) {
    }
}
