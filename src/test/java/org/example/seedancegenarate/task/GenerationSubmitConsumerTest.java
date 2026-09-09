package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationSubmitConsumerTest {

    private final AsyncJobService asyncJobService = mock(AsyncJobService.class);
    private final GenerationAttemptService attemptService = mock(GenerationAttemptService.class);
    private final GenerationAttemptMapper attemptMapper = mock(GenerationAttemptMapper.class);
    private final TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GenerationSubmitConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new GenerationSubmitConsumer(asyncJobService, attemptService, attemptMapper,
                transitioner, objectMapper, transactionTemplate());
    }

    @Test
    void submittedAttemptCompletesClaimedJob() {
        // 【测什么】供应商已接单时，Worker 只完成带 fencing 的当前 job。
        // 【怎么算红】没有 GENERATION_SUBMIT Consumer，作业永远不会从 RUNNING 收口。
        AsyncJob job = job(1L, 0, 5);
        prepareActive(701L, 91L);
        when(attemptService.execute(701L)).thenReturn(GenerationAttemptService.ExecuteResult.SUBMITTED);
        when(asyncJobService.complete(job)).thenReturn(true);

        consumer.execute(job);

        verify(asyncJobService).complete(job);
        verify(asyncJobService, never()).failAndRetry(eq(job), anyString());
    }

    @Test
    void unknownOutcomeIsParkedWithoutFailingTaskOrReleasingFunds() {
        // 【测什么】供应商结果未知时完成 job，但保留外部 PROCESSING 与冻结/并发槽。
        // 【怎么算红】若按普通异常重试或失败，会二次 submit 或错误退款、释放槽位。
        AsyncJob job = job(2L, 0, 5);
        prepareActive(702L, 91L);
        when(attemptService.execute(702L))
                .thenReturn(GenerationAttemptService.ExecuteResult.RECOVERY_REQUIRED);
        when(asyncJobService.complete(job)).thenReturn(true);

        consumer.execute(job);

        verify(asyncJobService).complete(job);
        verify(transitioner, never()).markFailed(eq(91L), anyString());
        verify(attemptService, never()).finishFailed(eq(702L), anyString());
    }

    @Test
    void exhaustedSafeRetryTerminalizesTaskBeforeClosingAttemptAndJob() {
        // 【测什么】明确未接单且重试耗尽时，先 renew ownership，再终态任务，最后收 attempt/job。
        // 【怎么算红】先 failAndRetry 进入 DEAD 会留下 DEAD+PROCESSING 崩溃窗口，这条必须变红。
        AsyncJob job = job(3L, 4, 5);
        prepareActive(703L, 91L);
        when(attemptService.execute(703L)).thenReturn(GenerationAttemptService.ExecuteResult.SAFE_RETRY);
        when(asyncJobService.renew(job, 300)).thenReturn(true);
        when(asyncJobService.complete(job)).thenReturn(true);
        when(transitioner.markFailedIfCurrent(any(VideoTask.class), anyString())).thenReturn(true);
        when(attemptService.finishFailed(eq(703L), anyString())).thenReturn(true);

        consumer.execute(job);

        verify(asyncJobService).renew(job, 300);
        verify(asyncJobService, never()).failAndRetry(eq(job), anyString());
        verify(attemptService).finishFailed(eq(703L), anyString());
        verify(transitioner).markFailedIfCurrent(any(VideoTask.class), anyString());
        verify(asyncJobService).complete(job);
    }

    @Test
    void lostFenceCannotFailAttemptOrTask() {
        // 【测什么】SAFE_RETRY 耗尽但 renew 返回 false 时停止所有业务写入。
        // 【怎么算红】忽略 false 会违反租约 fencing，旧 Worker 能错误终结新任务。
        AsyncJob job = job(4L, 4, 5);
        prepareActive(704L, 91L);
        when(attemptService.execute(704L)).thenReturn(GenerationAttemptService.ExecuteResult.SAFE_RETRY);
        when(asyncJobService.renew(job, 300)).thenReturn(false);

        consumer.execute(job);

        verify(attemptService, never()).finishFailed(eq(704L), anyString());
        verify(transitioner, never()).markFailedIfCurrent(any(VideoTask.class), anyString());
    }

    @Test
    void missingAttemptPayloadIsCompletedInsteadOfBecomingPoisonJob() {
        // 【测什么】明确查不到 attempt 的孤儿 payload 直接收掉，不无限过期重领。
        // 【怎么算红】attempt==null 继续 execute 时会重试/DEAD 或永久占用租约。
        AsyncJob job = job(5L, 0, 5);
        when(attemptMapper.selectById(705L)).thenReturn(null);

        consumer.execute(job);

        verify(asyncJobService).complete(job);
        verify(attemptService, never()).execute(705L);
    }

    @Test
    void lastUnknownInfrastructureFailureRetainsLeaseInsteadOfCreatingDeadProcessing() {
        // 【测什么】可能已接单的 generic 异常在最后一次只续租等待接管，不写 DEAD/业务失败。
        // 【怎么算红】最后一次仍 failAndRetry 会产生 DEAD+PROCESSING。
        AsyncJob job = job(6L, 4, 5);
        prepareActive(706L, 91L);
        doThrow(new IllegalStateException("db write failed")).when(attemptService).execute(706L);
        when(asyncJobService.renew(job, 300)).thenReturn(true);

        consumer.execute(job);

        verify(asyncJobService).renew(job, 300);
        verify(asyncJobService, never()).failAndRetry(eq(job), anyString());
        verify(transitioner, never()).markFailedIfCurrent(any(VideoTask.class), anyString());
    }

    @Test
    void completeFenceLossRollsBackTerminalTransaction() {
        // 【测什么】renew 后若 complete 仍返回 0，业务终态与 attempt 收口必须整体回滚。
        // 【怎么算红】忽略 complete=false 会提交 FAILED，但新 generation job 仍在运行，产生双写。
        AsyncJobService jobs = mock(AsyncJobService.class);
        GenerationAttemptService attempts = mock(GenerationAttemptService.class);
        GenerationAttemptMapper attemptRows = mock(GenerationAttemptMapper.class);
        TaskStatusTransitioner taskTransitions = mock(TaskStatusTransitioner.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        GenerationSubmitConsumer local = new GenerationSubmitConsumer(
                jobs, attempts, attemptRows, taskTransitions, objectMapper,
                new TransactionTemplate(manager));
        AsyncJob job = job(7L, 4, 5);
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(707L);
        attempt.setVideoTaskId(91L);
        attempt.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptRows.selectById(707L)).thenReturn(attempt);
        VideoTask task = new VideoTask();
        task.setId(91L);
        task.setStatus("PROCESSING");
        task.setCurrentAttemptId(707L);
        when(taskTransitions.findById(91L)).thenReturn(task);
        when(attempts.execute(707L)).thenReturn(GenerationAttemptService.ExecuteResult.SAFE_RETRY);
        when(jobs.renew(job, 300)).thenReturn(true);
        when(taskTransitions.markFailedIfCurrent(task, "供应商明确未接单，等待安全重试"))
                .thenReturn(true);
        when(attempts.finishFailed(707L, "供应商明确未接单，等待安全重试")).thenReturn(true);
        when(jobs.complete(job)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> local.execute(job));

        verify(manager).rollback(status);
    }

    @Test
    void obsoleteAttemptCannotFailCurrentReplacement() {
        // 【测什么】A 的旧 job 到来时任务 currentAttempt 已是 B，只收 A/job，绝不终结 B。
        // 【怎么算红】按 taskId 无条件 markFailed 会让旧 A 的耗尽结果杀掉正在运行的 B。
        AsyncJob job = job(8L, 4, 5);
        GenerationAttempt old = new GenerationAttempt();
        old.setId(708L);
        old.setVideoTaskId(91L);
        old.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptMapper.selectById(708L)).thenReturn(old);
        VideoTask replacement = new VideoTask();
        replacement.setId(91L);
        replacement.setStatus("PROCESSING");
        replacement.setCurrentAttemptId(999L);
        when(transitioner.findById(91L)).thenReturn(replacement);

        consumer.execute(job);

        verify(attemptService).finishFailed(708L, "生成轮次已被更新轮次替代");
        verify(transitioner, never()).markFailedIfCurrent(any(), anyString());
        verify(attemptService, never()).execute(708L);
        verify(asyncJobService).complete(job);
    }

    private void prepareActive(long attemptId, long taskId) {
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(attemptId);
        attempt.setVideoTaskId(taskId);
        attempt.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptMapper.selectById(attemptId)).thenReturn(attempt);
        VideoTask task = new VideoTask();
        task.setId(taskId);
        task.setStatus("PROCESSING");
        task.setPhase("QUEUED");
        task.setCurrentAttemptId(attemptId);
        when(transitioner.findById(taskId)).thenReturn(task);
    }

    private AsyncJob job(long id, int attempts, int maxAttempts) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType(GenerationAttemptService.JOB_TYPE);
        try {
            job.setPayload(objectMapper.writeValueAsString(
                    new GenerationAttemptService.JobPayload(700L + id)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        job.setStatus("RUNNING");
        job.setAttempts(attempts);
        job.setMaxAttempts(maxAttempts);
        job.setLeaseToken("lease-" + id);
        job.setLeaseGeneration(2L);
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        return job;
    }

    private TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new TransactionTemplate(manager);
    }
}
