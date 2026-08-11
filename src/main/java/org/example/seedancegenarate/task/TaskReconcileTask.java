package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 任务完成对账（兜底，低频）：统一按 next_poll_at 到期兜底——
 * 事件驱动任务（等回调）5 分钟查一次；轮询任务仅在 poller 卡死时接管。
 * 分布式锁保证多实例下只有一台执行；处理结果与回调/轮询走同一入口（updateStatus），幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReconcileTask {
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);
    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskPoller videoTaskPoller;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void reconcileOverdueTasks() {
        if (!lockProperties.isEnabled()) {
            // 单实例开发：未启用锁，直接执行（兼容旧行为）
            reconcileLocked();
            return;
        }
        AutoCloseable lock = distributedLock.tryLock("task-reconcile", LOCK_TTL);
        if (lock == null) {
            return; // 其他实例正在对账，或 Redis 不可用（fail-closed）
        }
        try (lock) {
            reconcileLocked();
        } catch (Exception e) {
            log.warn("任务对账失败: {}", e.getMessage());
        }
    }

    private void reconcileLocked() {
        // 统一按 next_poll_at 到期兜底：
        // - 事件驱动引擎任务：advanceTask 把 next_poll_at 设为 +5 分钟（等回调），到期再查一次；
        // - 轮询引擎任务：poller 每轮保持 next_poll_at 新鲜，对账只兜底 poller 卡死的场景。
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<VideoTask> wrapper = Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "PROCESSING")
                .isNotNull(VideoTask::getProviderTaskId)
                .ge(VideoTask::getCreateTime, now.minusHours(maxAgeHours))
                // NULL = 从未排期（事件驱动任务提交后）→ 立即可查；非 NULL 到期才查
                .and(w -> w.isNull(VideoTask::getNextPollAt)
                        .or()
                        .le(VideoTask::getNextPollAt, now))
                .orderByAsc(VideoTask::getId)
                .last("limit 100");
        List<VideoTask> overdue;
        try {
            overdue = videoTaskService.list(wrapper);
        } catch (Exception e) {
            log.warn("拉取超时任务失败: {}", e.getMessage());
            return;
        }
        if (!overdue.isEmpty()) {
            log.info("对账发现 {} 条待兜底任务", overdue.size());
        }
        for (VideoTask task : overdue) {
            try {
                videoTaskPoller.advanceTask(task);
            } catch (Exception e) {
                log.warn("对账推进任务 {} 失败: {}", task.businessTaskId(), e.getMessage());
            }
        }
    }
}
