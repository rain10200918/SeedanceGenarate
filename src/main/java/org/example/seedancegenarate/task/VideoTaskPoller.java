package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.GenerationState;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台任务推进器（只服务轮询机制引擎）：
 * <ul>
 *   <li>事件驱动引擎（CALLBACK，如 ComfyUI）：不轮询，等回调；对账任务低频兜底；</li>
 *   <li>轮询引擎（POLL，如 Seedance）：按 {@code next_poll_at} 退避查询，避免固定 2 秒忙等。</li>
 * </ul>
 * 分布式锁保证多实例下同一时刻只有一个实例执行；{@link #advanceTask} 供对账任务复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskPoller {
    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    /** 锁 TTL：单轮扫描通常远小于此；崩溃后由 TTL 自动让出。 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(300);

    @Value("${video.poll.enabled:true}")
    private boolean enabled;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    @Value("${video.poll.batch-size:200}")
    private int batchSize;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Scheduled(fixedDelayString = "${video.poll.interval-ms:2000}",
            initialDelayString = "${video.poll.initial-delay-ms:5000}")
    public void advanceProcessingTasks() {
        if (!enabled) {
            return;
        }
        if (!lockProperties.isEnabled()) {
            // 单实例开发：未启用锁，直接执行（兼容旧行为）
            advanceLocked();
            return;
        }
        // 分布式锁：多实例部署时同一时刻只有一个实例执行扫描；未拿到锁或 Redis 不可用
        // 都跳过本轮，避免所有实例同时重复轮询同一批任务（fail-closed）。
        AutoCloseable lock = distributedLock.tryLock("video-poller", LOCK_TTL);
        if (lock == null) {
            return;
        }
        try (lock) {
            advanceLocked();
        } catch (Exception e) {
            log.warn("推进器执行异常: {}", e.getMessage());
        }
    }

    private void advanceLocked() {
        // 需要轮询的引擎 = POLL 机制 + CALLBACK 但未配置回调（开发环境回退轮询）
        List<String> pollProviders = videoEngineRegistry.all().stream()
                .filter(VideoEngine::needsPolling)
                .map(VideoEngine::provider)
                .toList();
        if (pollProviders.isEmpty()) {
            return; // 全部引擎事件驱动且已配置回调：无任务需要轮询
        }
        List<VideoTask> tasks;
        try {
            // 只轮询「已提交完成」且「已到退避时间」的任务：provider_task_id 是提交链路的
            // 最后一步才回写，该字段为空说明 submit 仍在进行，此刻轮询会撞上提交竞态。
            tasks = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .in(VideoTask::getProvider, pollProviders)
                    .isNotNull(VideoTask::getProviderTaskId)
                    .ge(VideoTask::getCreateTime, LocalDateTime.now().minusHours(maxAgeHours))
                    .and(w -> w.isNull(VideoTask::getNextPollAt)
                            .or()
                            .le(VideoTask::getNextPollAt, LocalDateTime.now()))
                    .orderByAsc(VideoTask::getId)
                    .last("limit " + Math.max(batchSize, 1)));
        } catch (Exception e) {
            log.warn("拉取待推进任务失败: {}", e.getMessage());
            return;
        }
        if (!tasks.isEmpty()) {
            log.info("轮询推进器扫描到 {} 条任务", tasks.size());
        }
        for (VideoTask task : tasks) {
            try {
                advanceTask(task);
            } catch (Exception e) {
                // 单个任务轮询失败（网络抖动、节点暂时不可达等）不影响其他任务；
                // 不轻易置为 FAILED（那是提供方明确返回失败才做的），下一轮继续重试
                log.warn("推进任务 {} 失败: {}", task.businessTaskId(), e.getMessage());
            }
        }
    }

    /** 推进单个任务（poller 与对账任务共用）：poll → 落库 → 按退避更新下次轮询时间。 */
    public void advanceTask(VideoTask task) throws Exception {
        String provider = (task.getProvider() == null || task.getProvider().isBlank())
                ? defaultProvider : task.getProvider().trim();
        VideoEngine engine = videoEngineRegistry.get(provider);
        RemoteStatus status = engine.poll(task);
        videoTaskService.updateStatus(task, status);
        updateNextPollAt(task, status, engine);
    }

    /**
     * 退避策略（按引擎机制区分）：
     * <ul>
     *   <li>事件驱动引擎（已配回调）：poll 后 60 秒再兜底查一次——线上 ComfyUI 版本可能
     *       静默忽略 webhook_url，回调不可用；60 秒是「实时性 vs 查询量」的折中；</li>
     *   <li>轮询引擎：按任务已运行时长退避；SUCCESS 后 60 秒（等终态 Worker 收尾）。</li>
     * </ul>
     */
    private void updateNextPollAt(VideoTask task, RemoteStatus status, VideoEngine engine) {
        if (status.getState() == GenerationState.FAILED) {
            return; // 已落终态，无需再排期
        }
        LocalDateTime now = LocalDateTime.now();
        long delaySeconds;
        if (!engine.needsPolling()) {
            delaySeconds = 60; // 事件驱动：等回调，60 秒兜底查一次
        } else if (status.getState() == GenerationState.SUCCESS) {
            delaySeconds = 60; // 已入队终态作业，等待 Worker 收尾，不必高频复查
        } else {
            long ageSeconds = task.getCreateTime() == null
                    ? 0 : Duration.between(task.getCreateTime(), now).getSeconds();
            if (ageSeconds < 30) {
                delaySeconds = 2;
            } else if (ageSeconds < 300) {
                delaySeconds = 5;
            } else {
                delaySeconds = 30;
            }
        }
        videoTaskService.update(new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, task.getId())
                .set(VideoTask::getNextPollAt, now.plusSeconds(delaySeconds)));
    }
}
