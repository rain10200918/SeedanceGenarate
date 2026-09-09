package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskPollConsumerTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                VideoTask.class);
    }

    @Test
    void terminalOrRecoveryRequiredTaskIsNeverPolled() throws Exception {
        // 【测什么】迟到 poll 看到任务已终态或 RECOVERY_REQUIRED 时只收掉 job，不调 provider。
        // 【怎么算红】去掉执行前重读或 phase 守卫，engine.poll 会被调用而变红。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        when(engines.get(anyString())).thenReturn(engine);
        VideoTask task = processingTask();
        task.setPhase("RECOVERY_REQUIRED");
        when(tasks.getById(42L)).thenReturn(task);
        TaskPollConsumer consumer = consumer(jobs, tasks, engines, mock(TaskRetryPolicy.class),
                mock(TaskStatusTransitioner.class));
        AsyncJob job = pollJob();

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(engine, never()).poll(any());
    }

    @Test
    void providerGetCanRetryWithoutChangingMoneyOrBlindlyResubmitting() throws Exception {
        // 【测什么】普通 poll GET 超时只把当前 job fenced 退避，不转任务终态、不走重投策略。
        // 【怎么算红】把一次查询异常当失败/丢失处理，retryOrFail 或 complete 会被调用。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
        VideoTask task = processingTask();
        when(tasks.getById(42L)).thenReturn(task);
        when(engines.get("seedance")).thenReturn(engine);
        org.mockito.Mockito.doThrow(new IllegalStateException("timeout")).when(engine).poll(task);
        TaskPollConsumer consumer = consumer(jobs, tasks, engines, retryPolicy,
                mock(TaskStatusTransitioner.class));
        AsyncJob job = pollJob();

        consumer.execute(job);

        verify(jobs).failAndRetry(eq(job), eq("timeout"));
        verify(jobs, never()).complete(job);
        verify(retryPolicy, never()).retryOrFail(any(), anyString());
    }

    @Test
    void overduePollUsesTheSingleRetryPolicyAndCompletesItsReadJob() throws Exception {
        // 【测什么】poll 确认超龄仍 PROCESSING 时只调 TaskRetryPolicy，并收掉可重放的查询 job。
        // 【怎么算红】在 poll handler 另写一套重投分支，或超龄后继续 failAndRetry poll job，这条必须变红。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
        VideoTask task = processingTask();
        task.setLastAttemptAt(LocalDateTime.now().minusMinutes(61));
        when(tasks.getById(42L)).thenReturn(task);
        when(engines.get("seedance")).thenReturn(engine);
        when(engine.poll(task)).thenReturn(RemoteStatus.processing());
        TaskPollConsumer consumer = consumer(jobs, tasks, engines, retryPolicy,
                mock(TaskStatusTransitioner.class));
        AsyncJob job = pollJob();
        when(jobs.renew(job, 60)).thenReturn(true);
        when(jobs.complete(job)).thenReturn(true);

        consumer.execute(job);

        verify(retryPolicy).retryOrFail(task, engine, "任务执行超时");
        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), anyString());
    }

    @Test
    void providerResultCannotWriteAfterLeaseTakeover() throws Exception {
        // 【测什么】provider GET 返回后 renew=false 时，旧 Worker 不做任何任务写/重投/排期/complete。
        // 【怎么算红】renew 在业务写之后或独立事务提交，旧 generation 会覆盖接管者结果。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
        TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
        VideoTask old = processingTask();
        when(tasks.getById(42L)).thenReturn(old);
        when(engines.get("seedance")).thenReturn(engine);
        when(engine.poll(old)).thenReturn(RemoteStatus.success("https://provider.invalid/old.mp4"));
        AsyncJob job = pollJob();
        when(jobs.renew(job, 60)).thenReturn(false);

        consumer(jobs, tasks, engines, retryPolicy, transitioner).execute(job);

        verify(tasks, never()).updateStatus(any(), any());
        verify(jobs, never()).complete(job);
        verifyNoInteractions(retryPolicy, transitioner);
    }

    @Test
    void providerResultCannotCrossAttemptChangedDuringGet() throws Exception {
        // 【测什么】GET 阻塞期间 attempt 7 被 attempt 8 替换，旧 SUCCESS 只完成旧 poll job。
        // 【怎么算红】只在 GET 前校验身份会把旧产物 stage 到新轮次。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        TaskRetryPolicy retryPolicy = mock(TaskRetryPolicy.class);
        VideoTask old = processingTask();
        VideoTask replacement = processingTask();
        replacement.setCurrentAttemptId(8L);
        replacement.setProviderTaskId("remote-8");
        when(tasks.getById(42L)).thenReturn(old, replacement);
        when(engines.get("seedance")).thenReturn(engine);
        when(engine.poll(old)).thenReturn(RemoteStatus.success("https://provider.invalid/old.mp4"));
        AsyncJob job = pollJob();
        when(jobs.renew(job, 60)).thenReturn(true);
        when(jobs.complete(job)).thenReturn(true);

        consumer(jobs, tasks, engines, retryPolicy, mock(TaskStatusTransitioner.class)).execute(job);

        verify(tasks, never()).updateStatus(any(), any());
        verify(retryPolicy, never()).retryOrFail(any(), any(VideoEngine.class), anyString());
        verify(jobs).complete(job);
    }

    private TaskPollConsumer consumer(AsyncJobService jobs, VideoTaskService tasks,
                                      VideoEngineRegistry engines, TaskRetryPolicy retryPolicy,
                                      TaskStatusTransitioner transitioner) {
        TaskPollConsumer consumer = new TaskPollConsumer(
                jobs, tasks, engines, retryPolicy, transitioner, new ObjectMapper(),
                transactionTemplate());
        ReflectionTestUtils.setField(consumer, "timeoutMinutes", 60L);
        ReflectionTestUtils.setField(consumer, "defaultProvider", "seedance");
        return consumer;
    }

    private TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new TransactionTemplate(manager);
    }

    private VideoTask processingTask() {
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setBizTaskId("tsk_42");
        task.setStatus("PROCESSING");
        task.setPhase("RUNNING");
        task.setCurrentAttemptId(7L);
        task.setRetryCount(0);
        task.setProvider("seedance");
        task.setProviderTaskId("remote-42");
        task.setLastAttemptAt(LocalDateTime.now());
        return task;
    }

    private AsyncJob pollJob() throws Exception {
        AsyncJob job = new AsyncJob();
        job.setId(9L);
        job.setJobType(TaskPollConsumer.JOB_TYPE);
        job.setPayload(new ObjectMapper().writeValueAsString(
                new TaskPollConsumer.Payload(42L, 7L, "remote-42", "seedance", true)));
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setLeaseToken("lease");
        job.setLeaseGeneration(1L);
        return job;
    }
}
