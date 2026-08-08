package org.example.seedancegenarate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 流水线后台提交线程池：run 接口异步化用。
 * 单线程串行（引擎一次只吃一个任务，并发提交只会排队打爆引擎）；
 * 队列缓冲 + 停机时等待任务完成（避免重启丢提交循环）。
 */
@Configuration
public class PipelineConfig {

    @Bean("pipelineSubmitExecutor")
    public Executor pipelineSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pipeline-submit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
