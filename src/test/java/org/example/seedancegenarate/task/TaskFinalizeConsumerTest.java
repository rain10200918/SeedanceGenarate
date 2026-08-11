package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskFinalizeConsumerTest {

    static {
        // 纯 mock 环境无 MyBatis-Plus 初始化：手动装载实体元数据，使 LambdaUpdateWrapper 可用
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VideoTask.class);
    }

    @Test
    void completesJobAfterSuccessfulFinalization() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        AsyncJob job = claimedJob(1L, 0, 5, "{\"videoTaskId\":10,\"remoteVideoUrl\":\"https://x/a.mp4\"}");
        when(jobs.claimBatch(eq("TASK_FINALIZE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(jobs, tasks, properties(), new ObjectMapper());

        consumer.consumePendingFinalizes();

        verify(tasks).finalizeTask(10L, "https://x/a.mp4");
        verify(jobs).complete(1L, "token");
    }

    @Test
    void marksTaskFailedAndBacksOffWhenRetriesExhausted() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("下载失败"))
                .when(tasks).finalizeTask(eq(10L), any());
        AsyncJob job = claimedJob(1L, 4, 5, "{\"videoTaskId\":10,\"remoteVideoUrl\":\"https://x/a.mp4\"}");
        when(jobs.claimBatch(eq("TASK_FINALIZE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(jobs, tasks, properties(), new ObjectMapper());

        consumer.consumePendingFinalizes();

        verify(jobs).failAndRetry(eq(1L), eq("token"), any());
        verify(tasks).update(any(Wrapper.class));
    }

    @Test
    void completesJobWithoutRetryingWhenTaskAlreadyFinalizedByAnotherWorker() throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskService tasks = mock(VideoTaskService.class);
        // finalizeTask 对已终态任务幂等返回（CAS 失败不抛异常，mock 默认 no-op）
        AsyncJob job = claimedJob(1L, 0, 5, "{\"videoTaskId\":10,\"remoteVideoUrl\":\"https://x/a.mp4\"}");
        when(jobs.claimBatch(eq("TASK_FINALIZE"), any(Integer.class), any(Long.class)))
                .thenReturn(List.of(job));
        TaskFinalizeConsumer consumer = new TaskFinalizeConsumer(jobs, tasks, properties(), new ObjectMapper());

        consumer.consumePendingFinalizes();

        verify(jobs).complete(1L, "token");
        verify(tasks, never()).update(any(Wrapper.class));
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
        return job;
    }

    private AsyncJobProperties properties() {
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setClaimBatchSize(20);
        properties.setLeaseSeconds(60);
        return properties;
    }
}
