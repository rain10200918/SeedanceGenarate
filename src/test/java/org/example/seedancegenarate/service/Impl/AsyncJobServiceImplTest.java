package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.example.seedancegenarate.service.JobAvailableNotifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncJobServiceImplTest {

    @Test
    void enqueueUpsertsAndNotifiesConsumersWhenActuallyInserted() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.enqueueUpsert("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", "{}", 5)).thenReturn(1);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueue("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", "{}");

        verify(mapper).enqueueUpsert("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", "{}", 5);
        verify(notifier).notify("PIPELINE_NODE_SUBMIT");
    }

    @Test
    void doesNotNotifyWhenJobAlreadyActive() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        // 活跃作业重复入队影响 0 行：不通知，避免对账重复 poll 刷频道
        when(mapper.enqueueUpsert("TASK_FINALIZE", "task:10", "{}", 5)).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueue("TASK_FINALIZE", "task:10", "{}");

        org.mockito.Mockito.verifyNoInteractions(notifier);
    }

    @Test
    void claimReturnsOnlyJobsThatWonTheCas() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(candidate(10L), candidate(20L)));
        // 第一个抢到，第二个被并发 Worker 抢走（影响 0 行）
        when(mapper.claim(eq(10L), any(), any(), any(), any())).thenReturn(1);
        when(mapper.claim(eq(20L), any(), any(), any(), any())).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        List<AsyncJob> claimed = service.claimBatch("PIPELINE_NODE_SUBMIT", 20, 60);

        assertEquals(1, claimed.size());
        assertEquals(10L, claimed.get(0).getId());
        assertNotNull(claimed.get(0).getLeaseToken());
    }

    @Test
    void failAndRetryMarksDeadWhenAttemptsExhausted() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob job = candidate(10L);
        job.setAttempts(4);
        job.setMaxAttempts(5);
        when(mapper.selectById(10L)).thenReturn(job);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        service.failAndRetry(10L, "token", "boom");

        verify(mapper).failAndRetry(eq(10L), eq("token"), eq(AsyncJob.STATUS_DEAD), any(LocalDateTime.class), eq("boom"));
    }

    @Test
    void claimSingleReturnsNullWhenJobMissing() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        assertNull(service.claim("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", 60));
    }

    @Test
    void enqueueDelayedPassesDelaySecondsAndNotifies() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{\"orderNo\":\"ALP1\"}", 5, 600)).thenReturn(1);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{\"orderNo\":\"ALP1\"}", 600);

        verify(mapper).enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{\"orderNo\":\"ALP1\"}", 5, 600);
        verify(notifier).notify("ORDER_CLOSE");
    }

    @Test
    void enqueueDelayedDoesNotNotifyWhenJobAlreadyActive() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{}", 5, 600)).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{}", 600);

        org.mockito.Mockito.verifyNoInteractions(notifier);
    }

    private AsyncJob candidate(Long id) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType("PIPELINE_NODE_SUBMIT");
        job.setBizKey("pipeline:1:node:" + id);
        job.setPayload("{}");
        job.setStatus(AsyncJob.STATUS_READY);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        return job;
    }

    private AsyncJobProperties properties() {
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setMaxAttempts(5);
        properties.setBackoffBaseSeconds(30);
        return properties;
    }
}
