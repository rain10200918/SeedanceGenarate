package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.AsyncJobProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class JobAvailableNotifierTest {

    @Test
    void publishesJobTypeToConfiguredChannel() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setChannel("test:job-available");
        JobAvailableNotifier notifier = new JobAvailableNotifier(
                redis, properties, mock(ScheduledExecutorService.class));

        notifier.notify("TASK_FINALIZE");

        verify(redis).convertAndSend(eq("test:job-available"), contains("TASK_FINALIZE"));
    }

    @Test
    void swallowsRedisFailureSinceFallbackScanCoversIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis down"))
                .when(redis).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        JobAvailableNotifier notifier = new JobAvailableNotifier(
                redis, new AsyncJobProperties(), mock(ScheduledExecutorService.class));

        assertDoesNotThrow(() -> notifier.notify("TASK_FINALIZE"));
    }

    @Test
    void delayedDoorbellPublishesAtDueTimeInsteadOfImmediately() {
        // 【测什么】延迟作业在指定到期时间再次广播，而非只在尚不可领取的入队时刻广播。
        // 【怎么算红】退回“只 enqueue 时 notify”或未安排 timer，会看不到 schedule(5s)。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        JobAvailableNotifier notifier = new JobAvailableNotifier(redis, new AsyncJobProperties(), scheduler);

        notifier.notifyAfterDelay("TASK_POLL", 5);

        verify(scheduler).schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
                longThat(delay -> delay >= 5_000L && delay <= 6_000L), eq(TimeUnit.MILLISECONDS));
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    void delayedDoorbellsCoalescePerTypeAndDueSecond() {
        // 【测什么】同类型同一到期秒的一批任务只占一个 timer，避免充值峰值形成定时器/Redis 风暴。
        // 【怎么算红】恢复“每个 delayed job 一个 ScheduledFuture”，schedule 会被调用两次。
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        JobAvailableNotifier notifier = new JobAvailableNotifier(
                mock(StringRedisTemplate.class), new AsyncJobProperties(), scheduler);

        notifier.notifyAfterDelay("ORDER_CLOSE", 600);
        notifier.notifyAfterDelay("ORDER_CLOSE", 600);

        verify(scheduler, org.mockito.Mockito.times(1)).schedule(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void arbitraryFarFutureDelayCannotGrowTimerQueue() {
        // 【测什么】超过一天的任意 delay 不进入进程内 DelayQueue，正确性留给 MySQL 扫描。
        // 【怎么算红】公开入口无上限时，攻击/坏配置可按不同秒无限堆 ScheduledFuture。
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        JobAvailableNotifier notifier = new JobAvailableNotifier(
                mock(StringRedisTemplate.class), new AsyncJobProperties(), scheduler);

        notifier.notifyAfterDelay("TASK_POLL", TimeUnit.DAYS.toSeconds(2));

        verify(scheduler, never()).schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
