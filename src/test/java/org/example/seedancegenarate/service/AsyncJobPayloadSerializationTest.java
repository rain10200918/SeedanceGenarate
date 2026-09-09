package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.example.seedancegenarate.task.TaskPollConsumer;
import org.example.seedancegenarate.task.VideoTaskPoller;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncJobPayloadSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pollPayloadRoundTripsProviderIdentityControlCharacters() throws Exception {
        // 【测什么】TASK_POLL 身份字段含 \n/\r/\t/U+0001 时仍是合法且无损的 JSON。
        // 【怎么算红】回退手拼字符串只转义引号时，readTree 会失败，poll job 被当毒消息收掉。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskPoller poller = new VideoTaskPoller(mock(VideoTaskService.class), jobs, objectMapper);
        VideoTask task = processing("remote\n\r\t\u0001", "see\tdance\u0001");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        poller.enqueuePoll(task);

        verify(jobs).enqueue(eq(TaskPollConsumer.JOB_TYPE), eq("task:42:attempt:7"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(task.getProviderTaskId(), json.get("expectedProviderTaskId").asText());
        assertEquals(task.getProvider(), json.get("expectedProvider").asText());
    }

    @Test
    void retryPayloadRoundTripsProviderIdentityControlCharacters() throws Exception {
        // 【测什么】TASK_RETRY 同样统一走 ObjectMapper，不因提供方 ID 控制字符破坏 payload。
        // 【怎么算红】poll 修了而 retry 仍手拼 JSON 时，该 job 会解析失败后被直接 complete。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
        VideoEngine engine = mock(VideoEngine.class);
        when(engines.get(anyString())).thenReturn(engine);
        when(engine.timeoutRetrySupported()).thenReturn(true);
        TaskRetryPolicy policy = new TaskRetryPolicy(
                engines, jobs, mock(TaskStatusTransitioner.class), objectMapper);
        ReflectionTestUtils.setField(policy, "timeoutRetryMax", 2);
        ReflectionTestUtils.setField(policy, "defaultProvider", "seedance");
        VideoTask task = processing("remote\n\r\t\u0001", "see\tdance\u0001");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        policy.retryOrFail(task, "timeout");

        verify(jobs).enqueue(eq(VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY),
                eq("task:42:retry:0:attempt:7"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(task.getProviderTaskId(), json.get("expectedProviderTaskId").asText());
        assertEquals(task.getProvider(), json.get("expectedProvider").asText());
        assertEquals(task.getPhase(), json.get("expectedPhase").asText());
    }

    @Test
    void pollEnqueueRetriesDeadlockThroughTheTransactionalServiceProxy() {
        // 【测什么】轮询入队遇到瞬时死锁会重新调用服务，使第二次调用进入一张新事务。
        // 【怎么算红】删掉有界重试后第一次异常会直接冒出，且 enqueue 只会调用一次。
        AsyncJobService jobs = mock(AsyncJobService.class);
        VideoTaskPoller poller = new VideoTaskPoller(mock(VideoTaskService.class), jobs, objectMapper);
        VideoTask task = processing("remote-id", "seedance");
        String key = TaskPollConsumer.jobKey(task.getId(), task.getCurrentAttemptId());
        doThrow(new PessimisticLockingFailureException("deadlock", new SQLException("1213")))
                .doNothing()
                .when(jobs).enqueue(eq(TaskPollConsumer.JOB_TYPE), eq(key), anyString());

        poller.enqueuePoll(task);

        verify(jobs, times(2)).enqueue(eq(TaskPollConsumer.JOB_TYPE), eq(key), anyString());
    }

    private VideoTask processing(String providerTaskId, String provider) {
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setStatus("PROCESSING");
        task.setPhase("RUNNING");
        task.setCurrentAttemptId(7L);
        task.setRetryCount(0);
        task.setProviderTaskId(providerTaskId);
        task.setProvider(provider);
        return task;
    }
}
