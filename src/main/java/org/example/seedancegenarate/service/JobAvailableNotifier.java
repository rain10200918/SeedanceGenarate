package org.example.seedancegenarate.service;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作业可用通知（事件驱动）：入队成功后发布 Redis 消息唤醒消费 Worker，
 * 避免「每 2 秒查一次作业表」的忙等。通知丢失不影响正确性——消费 Worker
 * 保留低频兜底扫描。
 */
@Slf4j
@Component
public class JobAvailableNotifier {
    private static final long MAX_TIMER_DELAY_SECONDS = TimeUnit.DAYS.toSeconds(1);
    private static final int MAX_SCHEDULED_DOORBELLS = 4096;
    private final StringRedisTemplate redisTemplate;
    private final AsyncJobProperties properties;
    private final ScheduledExecutorService doorbellScheduler;
    /** 同一类型同一到期秒只保留一个铃；不能只保留“每类型最早”，否则会吞掉后续秒。 */
    private final Set<String> scheduledDoorbells = ConcurrentHashMap.newKeySet();

    public JobAvailableNotifier(StringRedisTemplate redisTemplate,
                                AsyncJobProperties properties,
                                @Qualifier("asyncJobDoorbellScheduler") ScheduledExecutorService doorbellScheduler) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.doorbellScheduler = doorbellScheduler;
    }

    public void notify(String jobType) {
        try {
            Long receivers = redisTemplate.convertAndSend(properties.getChannel(),
                    "{\"jobType\":\"" + jobType + "\"}");
            log.debug("已发布作业可用通知: jobType={}, channel={}, receivers={}",
                    jobType, properties.getChannel(), receivers);
        } catch (Exception e) {
            // 通知只是加速；失败由兜底扫描接管，不影响任务正确性
            log.warn("作业通知发布失败（兜底扫描将接管）: jobType={}, channel={}, reason={}",
                    jobType, properties.getChannel(), e.getMessage());
        }
    }

    /**
     * 在作业真正可领取时再广播一次 doorbell。定时器只是低延迟优化；进程若在到期前退出，
     * MySQL 作业仍由低频扫描接管。
     */
    public void notifyAfterDelay(String jobType, long delaySeconds) {
        long boundedDelay = Math.max(delaySeconds, 0);
        if (boundedDelay == 0) {
            notify(jobType);
            return;
        }
        if (boundedDelay > MAX_TIMER_DELAY_SECONDS
                || scheduledDoorbells.size() >= MAX_SCHEDULED_DOORBELLS) {
            // DelayQueue 不能由任意远期时间或无限不同到期秒撑大；MySQL + 低频扫描仍保证正确性。
            log.debug("跳过远期/过量 doorbell timer，等待兜底扫描: jobType={}, delaySeconds={}",
                    jobType, boundedDelay);
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long dueEpochSecond = Math.floorDiv(nowMillis, 1000L) + boundedDelay;
        String key = jobType + '@' + dueEpochSecond;
        if (!scheduledDoorbells.add(key)) {
            return;
        }
        // 在到期秒末尾广播，确保该秒内后入队且被合并的所有作业都已 available。
        long scheduleDelayMillis = Math.max((dueEpochSecond + 1) * 1000L - nowMillis, 1L);
        try {
            doorbellScheduler.schedule(() -> {
                try {
                    notify(jobType);
                } finally {
                    scheduledDoorbells.remove(key);
                }
            }, scheduleDelayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            scheduledDoorbells.remove(key);
            log.debug("延迟作业 doorbell 调度器已停止，等待兜底扫描: jobType={}", jobType);
        }
    }
}
