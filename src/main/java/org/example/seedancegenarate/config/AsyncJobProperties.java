package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 持久化异步作业配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "async-job")
public class AsyncJobProperties {
    /** 消费 Worker 兜底扫描间隔（毫秒）：事件通知丢失时由低频扫描兜底。 */
    private long reconcileIntervalMs = 30_000;
    /** 消费 Worker 首轮延迟（毫秒）。 */
    private long initialDelayMs = 10_000;
    /** Redis 作业可用通知频道（事件驱动：入队即唤醒消费，无需忙等）。 */
    private String channel = "local:seedance:event:job-available";
    /** 单轮领取上限。 */
    private int claimBatchSize = 20;
    /** 租约时长（秒）：超过后其他 Worker 可接管（崩溃恢复）。 */
    private long leaseSeconds = 60;
    /** 默认最大重试次数。 */
    private int maxAttempts = 5;
    /** 失败退避基准秒数（第 n 次失败 = base * 2^(n-1)）。 */
    private long backoffBaseSeconds = 30;
}
