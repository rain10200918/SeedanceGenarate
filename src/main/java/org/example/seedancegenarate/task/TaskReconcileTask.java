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
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.WalletService;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务完成对账（兜底，低频）：四分支，分布式锁保证多实例下只有一台执行：
 * <ol>
 *   <li><b>到期推进</b>：按 next_poll_at 到期兜底（事件驱动任务 60s 查一次；轮询任务仅在 poller 卡死时接管）；</li>
 *   <li><b>超龄决策</b>：本轮尝试（last_attempt_at）超过阈值仍 PROCESSING → 最后 poll 确认 →
 *       ON_SUCCESS 计费引擎（免费）入队 TASK_RETRY 自动重试；提交即计费引擎 / 重试耗尽 → 超时终止；</li>
 *   <li><b>提交断裂</b>：PROCESSING 且 provider_task_id 一直为空（提交链路断裂）→ 超时终止；</li>
 *   <li><b>账务补偿</b>：终态任务缺失 SETTLE/RELEASE 流水 → 按 biz_key 幂等补。</li>
 * </ol>
 * 处理结果与回调/轮询走同一入口（updateStatus / TaskStatusTransitioner），CAS + 幂等。
 * <p>
 * <b>四个分支各带独立时间预算</b>（见 {@link #perBranchBudgetMs()}）：分支①②要发网络请求，
 * 一台 hang 住的节点能把整轮拖过锁 TTL——那时另一实例会并发进来，且排在最后的账务补偿
 * 这一轮根本轮不到执行。超预算的活不会丢：四个分支查的都是「还没处理的活」，下一轮接着做。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReconcileTask {
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);

    /** 账务补偿连续失败到这个次数就升级为 ERROR 点名告警 */
    private static final int WALLET_COMPENSATION_ALERT_AFTER = 3;
    /**
     * 连续失败到这个次数就「隔离」：从主查询里排除，改为按小时单独重试。
     * 门槛刻意高于告警门槛 —— 先喊人，再隔离；30s 一轮意味着要连续失败约 5 分钟，
     * 几轮 DB 抖动这种瞬时故障不会被误隔离。
     */
    private static final int WALLET_COMPENSATION_QUARANTINE_AFTER = 10;
    /** 隔离行每这么多轮重试一次（30s 一轮 → 约每小时） */
    private static final int WALLET_QUARANTINE_RETRY_EVERY = 120;
    /** NOT IN 列表的上限：真正堵住队头的就是 id 最小那批，多了也没意义还拖慢查询 */
    private static final int WALLET_QUARANTINE_EXCLUDE_MAX = 200;

    /** taskId → 连续失败次数。成功即清零；进程重启后重新计数（重启本身就值得再喊一次） */
    private final Map<Long, Integer> walletCompensationFailures = new ConcurrentHashMap<>();

    /** 对账轮次，只用来给隔离行排小时级的重试节奏 */
    private final AtomicLong reconcileRounds = new AtomicLong();

    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;
    private final VideoTaskPoller videoTaskPoller;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final WalletService walletService;
    private final TaskRetryPolicy taskRetryPolicy;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    /** 超龄强制终态阈值（分钟）：本轮尝试超过该时长仍无结果则走决策树 */
    @Value("${video.task-timeout-minutes:60}")
    private long timeoutMinutes;

    /** 提交断裂判定阈值（分钟）：PROCESSING 且 provider_task_id 为空超过该时长视为提交断裂 */
    @Value("${video.submit-stall-minutes:10}")
    private long submitStallMinutes;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    /** 单轮对账的时间预算（毫秒），四个分支平分；实际生效值被锁 TTL 的一半夹死 */
    @Value("${video.reconcile-round-budget-ms:60000}")
    private long roundBudgetMs;

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
        // 四个分支各分一份预算，而不是共用一个总预算：共用时排在最后的账务补偿会被前面的
        // 慢分支饿死，而它恰恰最不能拖——钱没退回去，用户看得见。
        // 每个分支进入时重新起算，所以第一条永远做得成，不会出现整轮零进展的活锁。
        long round = reconcileRounds.incrementAndGet();
        long budgetMs = perBranchBudgetMs();
        reconcileDue(deadline(budgetMs));
        reconcileStalled(deadline(budgetMs));
        reconcileBrokenSubmit(deadline(budgetMs));
        reconcileWalletTransitions(deadline(budgetMs), round);
    }

    /**
     * 每个分支的时间预算。
     * <p>
     * 上限被<b>锁 TTL 的一半</b>夹死，配置写多大都不许突破：一轮跑过了租约时长，
     * 另一个实例就会拿到锁并发进来，而 {@code fixedDelay} 只防同实例重入、不防跨实例。
     * 留的另一半是余量——预算是在两条任务之间检查的，打断不了已经发出的那次 HTTP 调用。
     */
    long perBranchBudgetMs() {
        return Math.max(Math.min(roundBudgetMs, LOCK_TTL.toMillis() / 2), 0) / 4;
    }

    /** 用 nanoTime 不用墙钟：对账跑在后台，NTP 把系统时间拨一下不该让预算失效 */
    private static long deadline(long budgetMs) {
        return System.nanoTime() + budgetMs * 1_000_000L;
    }

    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * 账务补偿：任务状态与钱包终态动作必须最终一致。
     * 状态 CAS 与钱包事务不是同一事务（避免把外部任务推进拖进账务长事务），
     * 因此这里按任务扫描缺失的 SETTLE/RELEASE；WalletService 的 biz_key 唯一约束
     * 使重复补偿安全。多实例由外层 task-reconcile 锁串行，锁失效/宕机由下一轮继续。
     */
    private void reconcileWalletTransitions(long deadline, long round) {
        Set<Long> quarantined = quarantinedTaskIds();
        // 隔离行每小时单独重试一次。这一支的关键不是「再试试」，而是**脱离 7 天查询窗口**：
        // 原来毒行一过 7 天就静默掉出候选集，既不再重试也不再告警，钱永远错着且没人知道。
        if (!quarantined.isEmpty() && round % WALLET_QUARANTINE_RETRY_EVERY == 0) {
            retryQuarantined(quarantined, deadline);
        }
        List<VideoTask> terminalTasks;
        try {
            terminalTasks = videoTaskService.findTerminalMissingWalletTransition(100, quarantined);
        } catch (Exception e) {
            log.warn("拉取终态账务补偿任务失败: {}", e.getMessage());
            return;
        }
        compensate(terminalTasks, deadline, "账务补偿");
    }

    /** 按 id 取隔离集合，升序截断——真正堵住 {@code ORDER BY id ASC} 队头的就是最小那批 */
    private Set<Long> quarantinedTaskIds() {
        return walletCompensationFailures.entrySet().stream()
                .filter(e -> e.getValue() >= WALLET_COMPENSATION_QUARANTINE_AFTER)
                .map(Map.Entry::getKey)
                .sorted()
                .limit(WALLET_QUARANTINE_EXCLUDE_MAX)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * 隔离行重试：不走那条带 7 天窗口的查询，直接按 id 取。
     * 人把数据修好后这里会自动成功（settle/release 幂等），并解除隔离——不需要重启进程。
     */
    private void retryQuarantined(Set<Long> quarantined, long deadline) {
        List<VideoTask> tasks;
        try {
            tasks = videoTaskService.listByIds(quarantined);
        } catch (Exception e) {
            log.warn("拉取隔离中的账务补偿任务失败: {}", e.getMessage());
            return;
        }
        // 查不到的 id 说明任务已被删除：移出隔离，否则它会永远留在集合里空转。
        // 注意**不能改 quarantined 本身**——调用方紧接着要把它当主查询的 excludeIds 用，
        // 在这里删掉仍然补不好的 id，等于每 120 轮自己把隔离解除一次。
        Set<Long> found = tasks.stream().map(VideoTask::getId)
                .collect(java.util.stream.Collectors.toSet());
        quarantined.stream().filter(id -> !found.contains(id)).forEach(gone -> {
            walletCompensationFailures.remove(gone);
            log.warn("隔离中的账务补偿任务已不存在，解除隔离: dbId={}", gone);
        });
        compensate(tasks, deadline, "隔离重试");
    }

    /** 补偿一批任务：动作本身与改动前逐字一致（SUCCESS→settle / FAILED→release，biz_key 幂等） */
    private void compensate(List<VideoTask> tasks, long deadline, String label) {
        int processed = 0;
        for (VideoTask task : tasks) {
            if (processed > 0 && expired(deadline)) {
                log.warn("{}超出本轮预算，剩余 {} 条留待下一轮", label, tasks.size() - processed);
                break;
            }
            processed++;
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
                walletCompensationFailures.remove(task.getId()); // 修好了就清计数（也就解除了隔离）
            } catch (Exception e) {
                onWalletCompensationFailed(task, e);
            }
        }
    }

    /**
     * 补偿失败的告警策略：**不能每 30 秒重复喊同一句**。
     * <p>
     * 有些失败是自愈不了的（冻结额被历史 bug 挪用，池子里根本没钱可退），
     * 原来的写法会连喊 7 天两万次 WARN —— 等于没有告警，真问题被自己的噪声埋掉。
     * <p>
     * 节奏由<b>重试节奏本身</b>决定，不再靠 {@code count % 120} 猜轮次：
     * 到 {@link #WALLET_COMPENSATION_ALERT_AFTER} 喊一次 ERROR 点名；之后几次静默 WARN；
     * 到 {@link #WALLET_COMPENSATION_QUARANTINE_AFTER} 起进入隔离，此后每次尝试本身就已经是
     * 每小时一次，所以每次都喊。<b>只影响日志与重试节奏，不改任务状态、不放弃补偿</b> ——
     * 人把数据修好后会自动成功、清计数、解除隔离。
     */
    private void onWalletCompensationFailed(VideoTask task, Exception e) {
        int count = walletCompensationFailures.merge(task.getId(), 1, Integer::sum);
        boolean quarantined = count >= WALLET_COMPENSATION_QUARANTINE_AFTER;
        if (count == WALLET_COMPENSATION_ALERT_AFTER || quarantined) {
            log.error("终态账务补偿连续失败 {} 次{}，需人工处理: taskId={}, dbId={}, userId={}, "
                            + "status={}, freezeAmount={}, reason={}",
                    count, quarantined ? "（已隔离，改为每小时重试一次）" : "",
                    task.businessTaskId(), task.getId(), task.getUserId(),
                    task.getStatus(), task.getFreezeAmount(), e.getMessage());
        } else {
            log.warn("终态账务补偿失败: taskId={}, status={}, reason={}",
                    task.businessTaskId(), task.getStatus(), e.getMessage());
        }
    }

    /** 分支①：next_poll_at 到期兜底（现状保留）。 */
    private void reconcileDue(long deadline) {
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
        int processed = 0;
        for (VideoTask task : overdue) {
            if (processed > 0 && expired(deadline)) {
                log.warn("到期推进超出本轮预算，剩余 {} 条留待下一轮", overdue.size() - processed);
                break;
            }
            processed++;
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
    private void reconcileStalled(long deadline) {
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
        int processed = 0;
        for (VideoTask task : stalled) {
            if (processed > 0 && expired(deadline)) {
                log.warn("超龄决策超出本轮预算，剩余 {} 条留待下一轮", stalled.size() - processed);
                break;
            }
            processed++;
            try {
                VideoEngine engine = engineOf(task);
                RemoteStatus status = engine.poll(task);
                if (status.getState() != GenerationState.PROCESSING) {
                    // 引擎刚好完成：正常终态化（updateStatus 幂等）
                    videoTaskService.updateStatus(task, status);
                    continue;
                }
                taskRetryPolicy.retryOrFail(task, engine, "任务执行超时");
            } catch (Exception e) {
                // poll 异常（节点不可达等）：以前一律硬截止判失败——但节点死了不代表用户的活该失败，
                // 别的节点可能正闲着。可免费重投的引擎优先重投，重投耗尽才终止。
                log.warn("超龄任务最后确认失败: taskId={}, reason={}", task.businessTaskId(), e.getMessage());
                taskRetryPolicy.retryOrFail(task, "任务执行超时，且最后状态查询失败");
            }
        }
    }

    /** 决策：声明支持的引擎免费重试（未超上限入队）；其余超时终止。 */

    /**
     * 分支③：提交断裂兜底。提交链路异常中断会留下「PROCESSING + 空 provider_task_id」的
     * 僵尸行，poller/对账分支①②都只扫已提交任务（isNotNull provider_task_id），只有这里管。
     */
    private void reconcileBrokenSubmit(long deadline) {
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
        int processed = 0;
        for (VideoTask task : broken) {
            if (processed > 0 && expired(deadline)) {
                log.warn("提交断裂兜底超出本轮预算，剩余 {} 条留待下一轮", broken.size() - processed);
                break;
            }
            processed++;
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
