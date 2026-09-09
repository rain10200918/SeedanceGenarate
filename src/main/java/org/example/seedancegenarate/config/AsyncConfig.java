package org.example.seedancegenarate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@code @Async} 的执行器。
 * <p>
 * <b>为什么必须显式命名</b>：上下文里有多个 {@code TaskExecutor}（{@code pipelineSubmitExecutor}、
 * {@code taskScheduler}），且没有一个叫 {@code taskExecutor}。此时不带限定符的 {@code @Async}
 * 会回退到 {@code SimpleAsyncTaskExecutor} —— 那<b>不是线程池，是每次调用新建一条线程、
 * 无上限、不复用</b>。平时无感，并发一上来线程数就跟着请求数涨。
 * 2026-08-26 生产日志里那句 "More than one TaskExecutor bean found" 就是它。
 * <p>
 * 不复用 {@code pipelineSubmitExecutor}：那是 core=1/max=1 的单线程池，专供流水线提交循环，
 * 别的活挤进去会把流水线堵死。
 */
@Configuration
public class AsyncConfig {
    private static final int MAX_ASYNC_JOB_WORKERS = 64;

    /** 事件监听器专用（素材登记等增值副作用）：小池 + 有界队列 + 调用方兜底执行 */
    @Bean("eventListenerExecutor")
    public Executor eventListenerExecutor(
            @Value("${async.event-listener.core-size:2}") int coreSize,
            @Value("${async.event-listener.max-size:8}") int maxSize,
            @Value("${async.event-listener.queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("event-listener-");
        // 队列满了让调用线程自己跑，而不是丢弃：素材登记虽可容忍失败，
        // 但「悄悄丢掉」和「慢一点」相比，前者更难查。背压也顺带传回提交侧。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 只跑 claim/轮转的单线程，不执行业务 HTTP。 */
    @Bean("asyncJobDispatchExecutor")
    public Executor asyncJobDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("async-job-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 有界固定执行槽。队列只容纳“handler 已结束、Runnable 尚未从池返回”的交接窗口；
     * runtime 的 permit 仍保证业务执行+交接总数不超过 workerThreads。
     */
    @Bean("asyncJobWorkerExecutor")
    public Executor asyncJobWorkerExecutor(
            @Value("${async-job.worker-threads:8}") int workerThreads) {
        requireValidWorkerThreads(workerThreads);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workerThreads);
        executor.setMaxPoolSize(workerThreads);
        executor.setQueueCapacity(workerThreads);
        executor.setThreadNamePrefix("async-job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** heartbeat 不与长 HTTP 共用执行池，否则池满时恰好无法续租。 */
    @Bean(value = "asyncJobLeaseScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService asyncJobLeaseScheduler(
            @Value("${async-job.worker-threads:8}") int workerThreads) {
        requireValidWorkerThreads(workerThreads);
        // renew 是短 DB 写；每八个业务槽至少一条独立 heartbeat 线程，最多八条避免压垮连接池。
        int heartbeatThreads = Math.max(1, Math.min(8, (workerThreads + 7) / 8));
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(heartbeatThreads,
                new CustomizableThreadFactory("async-job-heartbeat-"));
        // 短作业会很快取消 heartbeat；立刻移除，避免 Future 在首次租期到达前堆积。
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** 延迟作业到期只负责发布 doorbell，不执行数据库或业务网络。 */
    @Bean(value = "asyncJobDoorbellScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService asyncJobDoorbellScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                new CustomizableThreadFactory("async-job-doorbell-"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void requireValidWorkerThreads(int workerThreads) {
        if (workerThreads < 1 || workerThreads > MAX_ASYNC_JOB_WORKERS) {
            throw new IllegalArgumentException("async-job.worker-threads must be between 1 and 64");
        }
    }
}
