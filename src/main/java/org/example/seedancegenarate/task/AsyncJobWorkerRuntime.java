package org.example.seedancegenarate.task;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多类型持久化作业的单机执行面：只有空闲槽才领取，每次只领一张，类型按轮转队列派发。
 * MySQL 是作业真相；Redis 通知只调 {@link #wake(String)} 加速，丢失后由带抖动的扫描恢复。
 */
@Slf4j
@Component
public class AsyncJobWorkerRuntime {
    private static final int MIN_WORKER_THREADS = 1;
    private static final int MAX_WORKER_THREADS = 64;
    private static final long MIN_SCAN_INTERVAL_MS = 1_000;
    private static final long MAX_SCAN_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);

    private final AsyncJobService asyncJobService;
    private final Map<String, AsyncJobHandler> handlers;
    private final AsyncJobProperties properties;
    private final Executor dispatchExecutor;
    private final Executor workerExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Semaphore freeSlots;
    private final Map<String, ConcurrencyGroup> groupsByType;
    private record ConcurrencyGroup(Semaphore slots, List<String> jobTypes) {}
    private final Queue<String> readyTypes = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedTypes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicLong nextScanAtNanos = new AtomicLong();
    private final AtomicLong jitterEntropy = new AtomicLong(System.nanoTime());

    public AsyncJobWorkerRuntime(
            AsyncJobService asyncJobService,
            List<AsyncJobHandler> handlers,
            AsyncJobProperties properties,
            @Qualifier("asyncJobDispatchExecutor") Executor dispatchExecutor,
            @Qualifier("asyncJobWorkerExecutor") Executor workerExecutor,
            @Qualifier("asyncJobLeaseScheduler") ScheduledExecutorService heartbeatExecutor) {
        this.asyncJobService = asyncJobService;
        this.handlers = indexHandlers(handlers);
        this.properties = properties;
        this.dispatchExecutor = dispatchExecutor;
        this.workerExecutor = workerExecutor;
        this.heartbeatExecutor = heartbeatExecutor;
        int workerThreads = properties.getWorkerThreads();
        if (workerThreads < MIN_WORKER_THREADS || workerThreads > MAX_WORKER_THREADS) {
            throw new IllegalArgumentException("async-job.worker-threads must be between 1 and 64");
        }
        requireScanConfig(properties.getReconcileIntervalMs(), properties.getReconcileJitterPercent());
        this.freeSlots = new Semaphore(workerThreads);
        this.groupsByType = indexGroups(this.handlers, workerThreads);
    }

    /** 同一 type 的密集通知只保留一个待调度标记。 */
    public void wake(String jobType) {
        if (!StringUtils.hasText(jobType)) {
            log.warn("忽略空作业类型通知");
            return;
        }
        String normalized = jobType.trim();
        AsyncJobHandler handler = handlers.get(normalized);
        if (handler == null) {
            log.warn("忽略未注册的作业类型: jobType={}", normalized);
            return;
        }
        if (!handler.enabled()) {
            return;
        }
        if (queuedTypes.add(normalized)) {
            readyTypes.offer(normalized);
        }
        scheduleDrain();
    }

    /** 通知丢失的低频兜底；每个实例的下次到期时间都带抖动。 */
    @Scheduled(fixedDelay = 1000L, initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void scanTick() {
        long now = System.nanoTime();
        long due = nextScanAtNanos.get();
        if (due != 0 && now - due < 0) {
            return;
        }
        long delayMs = jitteredDelay(properties.getReconcileIntervalMs(),
                properties.getReconcileJitterPercent(), jitterEntropy.getAndIncrement());
        if (!nextScanAtNanos.compareAndSet(due, now + TimeUnit.MILLISECONDS.toNanos(delayMs))) {
            return;
        }
        handlers.keySet().forEach(this::wake);
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatchExecutor.execute(this::drain);
        } catch (RejectedExecutionException e) {
            drainScheduled.set(false);
            log.debug("作业调度器已停止，等待其他实例接管");
        }
    }

    private void drain() {
        try {
            while (freeSlots.tryAcquire()) {
                String jobType = readyTypes.poll();
                if (jobType == null) {
                    freeSlots.release();
                    return;
                }
                // 先移除合并标记：claim 期间到达的新通知才能再入队，不会被这轮空查吞掉。
                queuedTypes.remove(jobType);
                AsyncJobHandler handler = handlers.get(jobType);
                if (handler == null || !handler.enabled()) {
                    freeSlots.release();
                    continue;
                }
                ConcurrencyGroup group = groupsByType.get(jobType);
                if (!group.slots().tryAcquire()) {
                    // Do not requeue a saturated group here: completion wakes every member type.
                    // Otherwise free generation slots cause a hot drain loop with no useful work.
                    freeSlots.release();
                    continue;
                }
                List<AsyncJob> claimed;
                try {
                    claimed = asyncJobService.claimBatch(jobType, 1, handler.leaseSeconds());
                } catch (Exception e) {
                    group.slots().release();
                    freeSlots.release();
                    log.warn("领取作业失败，留待兜底扫描: jobType={}, reason={}", jobType, e.getMessage());
                    continue;
                }
                if (claimed.isEmpty()) {
                    group.slots().release();
                    freeSlots.release();
                    continue;
                }
                AsyncJob lease = claimed.get(0);
                // 每领一张就回队尾，不让单个热 type 在空闲槽释放后永久插队。
                wake(jobType);
                try {
                    workerExecutor.execute(() -> runOne(handler, lease, group));
                } catch (RejectedExecutionException e) {
                    // 不伪造 fail/complete；已领作业会在租约过期后被其他实例接管。
                    group.slots().release();
                    freeSlots.release();
                    log.debug("作业执行池已停止: jobId={}", lease.getId());
                }
            }
        } finally {
            drainScheduled.set(false);
            if (freeSlots.availablePermits() > 0 && !readyTypes.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private void runOne(AsyncJobHandler handler, AsyncJob lease, ConcurrencyGroup group) {
        AtomicBoolean leaseLost = new AtomicBoolean();
        long heartbeatMs = Math.max(100L, TimeUnit.SECONDS.toMillis(handler.leaseSeconds()) / 3);
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(() -> {
                if (leaseLost.get()) {
                    return;
                }
                try {
                    if (!asyncJobService.renew(lease, handler.leaseSeconds())) {
                        leaseLost.set(true);
                        log.warn("作业租约已丢失，迟到收口将由业务 fencing 拒绝: jobId={}, generation={}",
                                lease.getId(), lease.getLeaseGeneration());
                    }
                } catch (Exception e) {
                    // 一次 DB 抖动不等于租约已被接管；下一次 heartbeat 继续尝试。
                    log.warn("作业续租失败: jobId={}, reason={}", lease.getId(), e.getMessage());
                }
            }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            handler.execute(lease);
        } catch (Exception e) {
            // runtime 无法猜测某类业务能否安全重试；保留 RUNNING，过期后按原 payload 接管。
            log.error("作业 handler 异常退出，等待租约接管: jobId={}, jobType={}, reason={}",
                    lease.getId(), handler.jobType(), e.getMessage(), e);
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            group.slots().release();
            freeSlots.release();
            group.jobTypes().forEach(this::wake);
            scheduleDrain();
        }
    }

    static long jitteredDelay(long baseMs, int jitterPercent, long entropy) {
        requireScanConfig(baseMs, jitterPercent);
        long span = baseMs * jitterPercent / 100;
        if (span == 0) {
            return baseMs;
        }
        long mixed = entropy ^ (entropy << 13) ^ (entropy >>> 7) ^ (entropy << 17);
        long offset = Math.floorMod(mixed, span * 2 + 1) - span;
        return baseMs + offset;
    }

    private static void requireScanConfig(long intervalMs, int jitterPercent) {
        if (intervalMs < MIN_SCAN_INTERVAL_MS || intervalMs > MAX_SCAN_INTERVAL_MS) {
            throw new IllegalArgumentException("async-job.reconcile-interval-ms must be between 1000 and 86400000");
        }
        if (jitterPercent < 0 || jitterPercent > 50) {
            throw new IllegalArgumentException("async-job.reconcile-jitter-percent must be between 0 and 50");
        }
    }

    private static Map<String, AsyncJobHandler> indexHandlers(List<AsyncJobHandler> candidates) {
        Map<String, AsyncJobHandler> indexed = new LinkedHashMap<>();
        for (AsyncJobHandler handler : candidates) {
            if (handler == null || !StringUtils.hasText(handler.jobType())) {
                throw new IllegalStateException("async job handler type is required");
            }
            String type = handler.jobType().trim();
            if (indexed.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("duplicate async job handler: " + type);
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, ConcurrencyGroup> indexGroups(Map<String, AsyncJobHandler> handlers, int workerThreads) {
        Map<String, List<String>> members = new LinkedHashMap<>();
        Map<String, Integer> limits = new LinkedHashMap<>();
        handlers.forEach((type, handler) -> {
            String name = handler.concurrencyGroup();
            int limit = handler.concurrencyLimit();
            if (!StringUtils.hasText(name) || limit < 1) {
                throw new IllegalStateException("async job concurrency group and positive limit are required: " + type);
            }
            name = name.trim();
            Integer existing = limits.putIfAbsent(name, limit);
            if (existing != null && existing != limit) {
                throw new IllegalStateException("conflicting concurrency group limits: " + name);
            }
            members.computeIfAbsent(name, ignored -> new ArrayList<>()).add(type);
        });
        Map<String, ConcurrencyGroup> indexed = new LinkedHashMap<>();
        members.forEach((name, types) -> {
            int configured = limits.get(name);
            int limit = configured == Integer.MAX_VALUE ? workerThreads
                    : Math.min(configured, Math.max(1, workerThreads - 1));
            ConcurrencyGroup group = new ConcurrencyGroup(new Semaphore(limit), List.copyOf(types));
            types.forEach(type -> indexed.put(type, group));
        });
        return Map.copyOf(indexed);
    }
}
