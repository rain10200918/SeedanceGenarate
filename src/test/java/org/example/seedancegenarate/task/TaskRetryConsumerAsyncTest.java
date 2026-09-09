package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.Impl.VideoSubmitServiceImpl;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class TaskRetryConsumerAsyncTest {

    @Test
    void reclaimedOuterRetryJobCannotStageAnotherAttempt() throws Exception {
        // 【测什么】重试事务已把 retry_count 从 0 加到 1 后，旧 TASK_RETRY 重领只完成自身。
        // 【怎么算红】不校验 payload 的 expectedRetryCount，宕机窗口会再创建一次远端生成。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoSubmitServiceImpl submit = mock(VideoSubmitServiceImpl.class);
        AsyncJob job = job("{\"videoTaskId\":42,\"expectedRetryCount\":0}");
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setStatus("PROCESSING");
        task.setRetryCount(1);
        task.setPhase("RUNNING");
        when(tasks.getById(42L)).thenReturn(task);
        TaskRetryConsumer consumer = new TaskRetryConsumer(jobs, tasks, submit,
                mock(TaskStatusTransitioner.class), new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "maxRetry", 2);

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(submit, never()).resubmit(task);
    }

    @Test
    void rolledBackResubmitMutationCannotMakeExhaustionLookObsolete() throws Exception {
        // 【测什么】resubmit 原地改了 Java task 后又抛错，耗尽裁决仍使用进入事务前的 attempt 身份。
        // 【怎么算红】直接复用被 mutate 的对象，会误判为“旧 job 已 obsolete”并 complete，DB 任务永久 PROCESSING。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoSubmitServiceImpl submit = mock(VideoSubmitServiceImpl.class);
        TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
        AsyncJob job = job("{\"videoTaskId\":42,\"expectedRetryCount\":0,"
                + "\"expectedAttemptId\":7,\"expectedProviderTaskId\":\"remote-7\","
                + "\"expectedPhase\":\"RUNNING\",\"expectedProvider\":\"seedance\"}");
        job.setAttempts(4);
        job.setMaxAttempts(5);
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setStatus("PROCESSING");
        task.setRetryCount(0);
        task.setPhase("RUNNING");
        task.setCurrentAttemptId(7L);
        task.setProviderTaskId("remote-7");
        task.setProvider("seedance");
        when(tasks.getById(42L)).thenReturn(task);
        doAnswer(invocation -> {
            VideoTask mutable = invocation.getArgument(0);
            mutable.setRetryCount(1);
            mutable.setCurrentAttemptId(8L);
            mutable.setProviderTaskId(null);
            mutable.setPhase("QUEUED");
            throw new IllegalStateException("stage enqueue failed");
        }).when(submit).resubmit(task);
        when(jobs.renew(job, 300)).thenReturn(true);
        when(transitioner.markTimedOutIfCurrent(any(VideoTask.class), anyString())).thenReturn(true);
        when(transitioner.findById(42L)).thenReturn(task);
        when(jobs.complete(job)).thenReturn(true);
        TaskRetryConsumer consumer = new TaskRetryConsumer(jobs, tasks, submit,
                transitioner, new ObjectMapper(), transactionTemplate());
        ReflectionTestUtils.setField(consumer, "maxRetry", 2);

        consumer.execute(job);

        org.mockito.ArgumentCaptor<VideoTask> expected =
                org.mockito.ArgumentCaptor.forClass(VideoTask.class);
        verify(transitioner).markTimedOutIfCurrent(expected.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertEquals(7L, expected.getValue().getCurrentAttemptId());
        org.junit.jupiter.api.Assertions.assertEquals("remote-7", expected.getValue().getProviderTaskId());
        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", expected.getValue().getPhase());
        org.junit.jupiter.api.Assertions.assertEquals(0, expected.getValue().getRetryCount());
        verify(jobs).complete(job);
    }

    private AsyncJob job(String payload) {
        AsyncJob job = new AsyncJob();
        job.setId(80L);
        job.setPayload(payload);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setLeaseToken("lease");
        job.setLeaseGeneration(1L);
        return job;
    }

    private TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new TransactionTemplate(manager);
    }
}
