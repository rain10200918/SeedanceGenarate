package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.engine.GenerationState;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.WalletService;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务完成对账（兜底，低频）：三分支，分布式锁保证多实例下只有一台执行：
 * <ol>
 *   <li><b>到期推进</b>：按 next_poll_at 到期兜底（事件驱动任务 60s 查一次；轮询任务仅在 poller 卡死时接管）；</li>
 *   <li><b>超龄决策</b>：本轮尝试（last_attempt_at）超过阈值仍 PROCESSING → 最后 poll 确认 →
 *       ON_SUCCESS 计费引擎（免费）入队 TASK_RETRY 自动重试；提交即计费引擎 / 重试耗尽 → 超时终止；</li>
 *   <li><b>提交断裂</b>：PROCESSING 且 provider_task_id 一直为空（提交链路断裂）→ 超时终止。</li>
 * </ol>
 * 处理结果与回调/轮询走同一入口（updateStatus / TaskStatusTransitioner），CAS + 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReconcileTask {
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);
    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskPoller videoTaskPoller;
    private final AsyncJobService asyncJobService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final WalletService walletService;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    /** 超龄强制终态阈值（分钟）：本轮尝试超过该时长仍无结果则走决策树 */
    @Value("${video.task-timeout-minutes:60}")
    private long timeoutMinutes;

    /** 自动重试上限（仅声明支持超时免费重试的引擎；0 = 只失败不重试） */
    @Value("${video.timeout-retry-max:2}")
    private int timeoutRetryMax;

    /** 提交断裂判定阈值（分钟）：PROCESSING 且 provider_task_id 为空超过该时长视为提交断裂 */
    @Value("${video.submit-stall-minutes:10}")
    private long submitStallMinutes;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

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
        reconcileDue();
        reconcileStalled();
        reconcileBrokenSubmit();
        reconcileWalletTransitions();
    }

    /**
     * 账务补偿：任务状态与钱包终态动作必须最终一致。
     * 状态 CAS 与钱包事务不是同一事务（避免把外部任务推进拖进账务长事务），
     * 因此这里按任务扫描缺失的 SETTLE/RELEASE；WalletService 的 biz_key 唯一约束
     * 使重复补偿安全。多实例由外层 task-reconcile 锁串行，锁失效/宕机由下一轮继续。
     */
    private void reconcileWalletTransitions() {
        List<VideoTask> terminalTasks;
        try {
            terminalTasks = videoTaskService.findTerminalMissingWalletTransition(100);
        } catch (Exception e) {
            log.warn("拉取终态账务补偿任务失败: {}", e.getMessage());
            return;
        }
        for (VideoTask task : terminalTasks) {
            try {
                // 只查询缺失流水的任务；WalletService 再以 biz_key 做最终幂等保护。
                BigDecimal amount = task.getFreezeAmount();
                if (amount == null || amount.signum() <= 0) {
                    continue;
                }
                if ("SUCCESS".equals(task.getStatus())) {
                    walletService.settle(task.getUserId(), amount, task.getId());
                } else {
                    walletService.release(task.getUserId(), amount, task.getId());
                }
            } catch (Exception e) {
                log.warn("终态账务补偿失败: taskId={}, status={}, reason={}",
                        task.businessTaskId(), task.getStatus(), e.getMessage());
            }
        }
    }

    /** 分支①：next_poll_at 到期兜底（现状保留）。 */
    private void reconcileDue() {
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

    /**
     * 分支②：超龄任务决策。本轮尝试（last_attempt_at，首次=create_time）超过阈值仍
     * PROCESSING → 最后 poll 确认（引擎可能刚好完成）→ 仍无结果则按引擎重试能力决策。
     * 超时是硬截止：poll 异常（节点不可达）也直接终止，不再无限等待。
     */
    private void reconcileStalled() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<VideoTask> stalled;
        try {
            stalled = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .isNotNull(VideoTask::getProviderTaskId)
                    .lt(VideoTask::getLastAttemptAt, cutoff)
                    .orderByAsc(VideoTask::getId)
                    .last("limit 50"));
        } catch (Exception e) {
            log.warn("拉取超龄任务失败: {}", e.getMessage());
            return;
        }
        for (VideoTask task : stalled) {
            try {
                VideoEngine engine = engineOf(task);
                RemoteStatus status = engine.poll(task);
                if (status.getState() != GenerationState.PROCESSING) {
                    // 引擎刚好完成：正常终态化（updateStatus 幂等）
                    videoTaskService.updateStatus(task, status);
                    continue;
                }
                decideRetry(task, engine);
            } catch (Exception e) {
                // poll 异常（节点不可达等）：超时硬截止，不再等
                log.warn("超龄任务最后确认失败，直接终止: taskId={}, reason={}",
                        task.businessTaskId(), e.getMessage());
                taskStatusTransitioner.markTimedOut(task.getId(), "任务执行超时，且最后状态查询失败");
            }
        }
    }

    /** 决策：声明支持的引擎免费重试（未超上限入队）；其余超时终止。 */
    private void decideRetry(VideoTask task, VideoEngine engine) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (engine.timeoutRetrySupported() && retryCount < timeoutRetryMax) {
            log.info("任务执行超时，入队自动重试: taskId={}, 第 {} 次", task.businessTaskId(), retryCount + 1);
            // 幂等键 task:{id}：重复入队影响 0 行不重复通知；重提交由 Worker CAS 抢占只执行一次
            asyncJobService.enqueue(VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY, "task:" + task.getId(),
                    "{\"videoTaskId\":" + task.getId() + "}");
            return;
        }
        String reason = engine.timeoutRetrySupported()
                ? "任务执行超时，已自动重试 " + retryCount + " 次仍超时，已终止（可手动重试）"
                : "任务执行超时，已终止（可手动重试）";
        taskStatusTransitioner.markTimedOut(task.getId(), reason);
    }

    /**
     * 分支③：提交断裂兜底。提交链路异常中断会留下「PROCESSING + 空 provider_task_id」的
     * 僵尸行，poller/对账分支①②都只扫已提交任务（isNotNull provider_task_id），只有这里管。
     */
    private void reconcileBrokenSubmit() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(submitStallMinutes);
        List<VideoTask> broken;
        try {
            broken = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .isNull(VideoTask::getProviderTaskId)
                    .lt(VideoTask::getCreateTime, cutoff)
                    .orderByAsc(VideoTask::getId)
                    .last("limit 50"));
        } catch (Exception e) {
            log.warn("拉取提交断裂任务失败: {}", e.getMessage());
            return;
        }
        for (VideoTask task : broken) {
            log.warn("提交断裂，终止任务: taskId={}, provider={}", task.businessTaskId(), task.getProvider());
            taskStatusTransitioner.markTimedOut(task.getId(), "任务提交未完成，已终止（可重新提交）");
        }
    }

    private VideoEngine engineOf(VideoTask task) {
        String provider = task.getProvider();
        return videoEngineRegistry.get((provider == null || provider.isBlank())
                ? defaultProvider : provider.trim());
    }
}
