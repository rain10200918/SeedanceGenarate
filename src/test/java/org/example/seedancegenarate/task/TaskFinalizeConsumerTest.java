package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskFinalizeConsumerTest {

    @Test
    void completesJobAfterSuccessfulFinalization() throws Exception {
        // 【测什么】当前 attempt 的转存成功后收掉这一代租约。
        // 【怎么算红】丢失 payload identity 或 finalizeTask 成功后不 complete 时，这条必须变红。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoTask task = finalizingTask(10L, 7L, "remote-7");
        when(tasks.getById(10L)).thenReturn(task);
        AsyncJob job = claimedJob(1L, 0, 5, payload(10L, 7L, "remote-7"));
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(
                jobs, tasks, mock(TaskStatusTransitioner.class), new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(tasks).finalizeTask(task, "https://x/a.mp4", job, 300);
        verify(jobs, never()).complete(job); // job 与 SUCCESS 由 service 内部同事务完成
    }

    @Test
    void exhaustedFinalizeTerminalizesTaskBeforeCompletingJob() throws Exception {
        // 【测什么】最后一次先 renew，再用 execution identity 终态任务，最后 complete job。
        // 【怎么算红】先 failAndRetry 会重现 DEAD+PROCESSING，或无 identity 会误伤新 attempt。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
        VideoTask task = finalizingTask(10L, 7L, "remote-7");
        when(tasks.getById(10L)).thenReturn(task);
        AsyncJob job = claimedJob(1L, 4, 5, payload(10L, 7L, "remote-7"));
        when(jobs.complete(job)).thenReturn(true);
        doThrow(new IllegalStateException("下载失败")).when(tasks)
                .finalizeTask(eq(task), any(), eq(job), eq(300L));
        when(jobs.renew(job, 300)).thenReturn(true);
        when(transitioner.markFailedIfCurrent(eq(task), any())).thenReturn(true);
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(
                jobs, tasks, transitioner, new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(jobs).renew(job, 300);
        verify(jobs, never()).failAndRetry(eq(job), any());
        verify(transitioner).markFailedIfCurrent(eq(task), any());
        verify(jobs).complete(job);
    }

    @Test
    void staleLeaseCannotMarkVideoTaskFailed() throws Exception {
        // 【测什么】最后一次但 renew 失败的旧 owner 不得写业务终态。
        // 【怎么算红】忽略 renew false 会让旧 Worker 覆盖新 owner。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
        VideoTask task = finalizingTask(10L, 7L, "remote-7");
        when(tasks.getById(10L)).thenReturn(task);
        AsyncJob job = claimedJob(1L, 4, 5, payload(10L, 7L, "remote-7"));
        when(jobs.complete(job)).thenReturn(true);
        doThrow(new IllegalStateException("下载失败")).when(tasks)
                .finalizeTask(eq(task), any(), eq(job), eq(300L));
        when(jobs.renew(job, 300)).thenReturn(false);
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(
                jobs, tasks, transitioner, new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(transitioner, never()).markFailedIfCurrent(any(), any());
        verify(jobs, never()).complete(job);
    }

    @Test
    void obsoleteAttemptJobCompletesWithoutDownloading() throws Exception {
        // 【测什么】旧 attempt 的 finalize payload 遇到新 current attempt 时直接收掉。
        // 【怎么算红】只按 taskId 处理会下载旧产物并可能覆盖新轮次。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoTask current = finalizingTask(10L, 8L, "remote-8");
        when(tasks.getById(10L)).thenReturn(current);
        AsyncJob job = claimedJob(1L, 0, 5, payload(10L, 7L, "remote-7"));
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(
                jobs, tasks, mock(TaskStatusTransitioner.class), new ObjectMapper(), transactionTemplate());

        consumer.execute(job);

        verify(tasks, never()).finalizeTask(any(), any(), any(), anyLong());
        verify(jobs).complete(job);
    }

    private String payload(long taskId, long attemptId, String providerTaskId) {
        return "{\"videoTaskId\":" + taskId
                + ",\"expectedAttemptId\":" + attemptId
                + ",\"expectedProviderTaskId\":\"" + providerTaskId
                + "\",\"expectedProvider\":\"seedance\""
                + ",\"expectedPhase\":\"FINALIZING\""
                + ",\"expectedRetryCount\":0"
                + ",\"remoteVideoUrl\":\"https://x/a.mp4\"}";
    }

    private VideoTask finalizingTask(long id, long attemptId, String providerTaskId) {
        VideoTask task = new VideoTask();
        task.setId(id);
        task.setStatus("PROCESSING");
        task.setPhase("FINALIZING");
        task.setRetryCount(0);
        task.setCurrentAttemptId(attemptId);
        task.setProviderTaskId(providerTaskId);
        task.setProvider("seedance");
        return task;
    }

    private AsyncJob claimedJob(Long id, int attempts, int maxAttempts, String payload) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType("TASK_FINALIZE");
        job.setPayload(payload);
        job.setStatus(AsyncJob.STATUS_RUNNING);
        job.setAttempts(attempts);
        job.setMaxAttempts(maxAttempts);
        job.setLeaseToken("token");
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
