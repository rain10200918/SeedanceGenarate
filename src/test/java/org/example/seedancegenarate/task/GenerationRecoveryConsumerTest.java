package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationRecoveryConsumerTest {

    private final AsyncJobService jobs = mock(AsyncJobService.class);
    private final GenerationAttemptService attempts = mock(GenerationAttemptService.class);
    private GenerationRecoveryConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new GenerationRecoveryConsumer(jobs, attempts, new ObjectMapper());
    }

    @Test
    void foundPromptCompletesRecoveryJob() throws Exception {
        // 【测什么】找到既有 promptId 后恢复作业完成，不再进入重试。
        // 【怎么算红】若 RECOVERED 仍 failAndRetry，会重复查询甚至重复处理，本测试必须失败。
        AsyncJob job = job();
        when(attempts.recover(4L)).thenReturn(GenerationAttemptService.RecoveryResult.RECOVERED);

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), anyString());
    }

    @Test
    void unavailableNodeUsesPersistentBackoff() throws Exception {
        // 【测什么】节点仍返回 502 时保留恢复作业并走数据库退避，不把任务终结。
        // 【怎么算红】若异常被 complete 吞掉，GPU 恢复后任务仍会永久停放，本测试必须失败。
        AsyncJob job = job();
        when(attempts.recover(4L)).thenThrow(new RuntimeException("502 Bad Gateway"));

        consumer.execute(job);

        verify(jobs).failAndRetry(job, "502 Bad Gateway");
        verify(jobs, never()).complete(job);
    }

    @Test
    void notFoundStopsAtManualReviewWithoutSubmitting() throws Exception {
        // 【测什么】节点可达但找不到 trace 时收掉自动恢复作业，保留任务给管理员处置。
        // 【怎么算红】若 MANUAL_REQUIRED 继续 failAndRetry，会形成永不结束的后台查询，本测试必须失败。
        AsyncJob job = job();
        when(attempts.recover(4L)).thenReturn(GenerationAttemptService.RecoveryResult.MANUAL_REQUIRED);

        consumer.execute(job);

        verify(jobs).complete(job);
        verify(jobs, never()).failAndRetry(eq(job), anyString());
    }

    private AsyncJob job() {
        AsyncJob job = new AsyncJob();
        job.setId(77L);
        job.setJobType(GenerationAttemptService.RECOVERY_JOB_TYPE);
        job.setPayload("{\"attemptId\":4}");
        job.setStatus(AsyncJob.STATUS_RUNNING);
        job.setLeaseToken("lease");
        job.setLeaseGeneration(2L);
        return job;
    }
}
