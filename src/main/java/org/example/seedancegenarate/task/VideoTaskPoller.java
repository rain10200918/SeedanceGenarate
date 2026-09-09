package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 轮询作业生产器：只从 MySQL Writer 找到期任务并幂等入队 {@link TaskPollConsumer#JOB_TYPE}。
 * 这里不调 provider；多实例重复扫描由 (job_type,biz_key) 唯一约束收口，Redis 不是正确性前提。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskPoller {
    private static final int ENQUEUE_MAX_ATTEMPTS = 3;
    private static final long ENQUEUE_RETRY_MIN_MILLIS = 20;
    private static final long ENQUEUE_RETRY_MAX_MILLIS = 100;

    private final VideoTaskService videoTaskService;
    private final AsyncJobService asyncJobService;
    private final ObjectMapper objectMapper;

    @Value("${video.poll.enabled:true}")
    private boolean enabled;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    @Value("${video.poll.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${video.poll.interval-ms:2000}",
            initialDelayString = "${video.poll.initial-delay-ms:5000}")
    public void advanceProcessingTasks() {
        if (!enabled) {
            return;
        }
        List<VideoTask> tasks;
        LocalDateTime now = LocalDateTime.now();
        try {
            tasks = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .isNotNull(VideoTask::getProviderTaskId)
                    .and(w -> w.isNull(VideoTask::getPhase)
                            .or().eq(VideoTask::getPhase, "RUNNING"))
                    .ge(VideoTask::getCreateTime, now.minusHours(Math.max(maxAgeHours, 1)))
                    .and(w -> w.isNull(VideoTask::getNextPollAt)
                            .or().le(VideoTask::getNextPollAt, now))
                    .orderByAsc(VideoTask::getId)
                    .last("limit " + Math.min(Math.max(batchSize, 1), 1000)));
        } catch (Exception e) {
            log.warn("拉取待入队轮询任务失败: {}", e.getMessage());
            return;
        }
        int processed = 0;
        for (VideoTask task : tasks) {
            try {
                enqueuePoll(task);
                processed++;
            } catch (PessimisticLockingFailureException e) {
                // 单条连续三次都成为死锁牺牲者时留给下一轮；不能让它中断整批。
                log.warn("轮询作业入队连续死锁，留待下一轮: taskId={}", task.getId());
            }
        }
        if (processed > 0) {
            log.info("已检查 {} 张轮询作业", processed);
        }
    }

    /** 对账与周期生产器共用的唯一入队口，无网络 I/O。 */
    public void enqueuePoll(VideoTask task) {
        if (task == null || task.getId() == null || task.getId() <= 0
                || !"PROCESSING".equals(task.getStatus())
                || (StringUtils.hasText(task.getPhase()) && !"RUNNING".equals(task.getPhase()))
                || !StringUtils.hasText(task.getProviderTaskId())) {
            return;
        }
        String jobKey = TaskPollConsumer.jobKey(task.getId(), task.getCurrentAttemptId());
        String payload = pollPayload(task);
        for (int attempt = 1; attempt <= ENQUEUE_MAX_ATTEMPTS; attempt++) {
            try {
                // AsyncJobService 是 Spring 代理；异常越过事务代理后原事务已经回滚，
                // 下一次调用会进入一张新事务，不能在被回滚的事务内部重试。
                asyncJobService.enqueue(TaskPollConsumer.JOB_TYPE, jobKey, payload);
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == ENQUEUE_MAX_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeEnqueueRetry();
            }
        }
    }

    private void sleepBeforeEnqueueRetry() {
        long delayMillis = ThreadLocalRandom.current().nextLong(
                ENQUEUE_RETRY_MIN_MILLIS, ENQUEUE_RETRY_MAX_MILLIS + 1);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询作业入队重试被中断", e);
        }
    }

    private String pollPayload(VideoTask task) {
        try {
            return objectMapper.writeValueAsString(new PollPayload(task.getId(),
                    task.getCurrentAttemptId(), task.getProviderTaskId(), task.getProvider()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化轮询作业", e);
        }
    }

    record PollPayload(Long videoTaskId, Long expectedAttemptId,
                       String expectedProviderTaskId, String expectedProvider) {
    }
}
