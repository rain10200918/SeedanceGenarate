package org.example.seedancegenarate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
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
}
