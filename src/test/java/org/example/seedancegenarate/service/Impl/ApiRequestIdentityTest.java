package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.*;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiRequestIdentityTest {
    final ApiCallLogMapper logs = mock(ApiCallLogMapper.class);
    final VideoSubmitService submit = mock(VideoSubmitService.class);
    final VideoEngine engine = mock(VideoEngine.class);
    final VideoEngineRegistry registry = mock(VideoEngineRegistry.class);
    final ApiReferenceStorage storage = mock(ApiReferenceStorage.class);
    final VideoTaskService tasks = mock(VideoTaskService.class);
    final ApiKey key = new ApiKey();
    ApiVideoServiceImpl api;

    @BeforeEach void setup() throws Exception {
        key.setId(1L); key.setUserId(2L);
        when(engine.provider()).thenReturn("test");
        when(engine.models()).thenReturn(List.of(new ModelSpec("test", "model", "Model", false,
                0, 1, List.of("16:9"), 5, 10, List.of(5, 8, 10))));
        when(registry.all()).thenReturn(List.of(engine));
        api = spy(new ApiVideoServiceImpl(logs, submit, registry, storage, tasks));
        doThrow(new AssertionError("real network forbidden")).when(api).openReferenceConnection(any());
        when(logs.linkTaskByRequestId(any(), any(), anyString(), anyString(), any())).thenReturn(1);
        var task = new VideoTask(); task.setBizTaskId("tsk_identity"); task.setApiKeyId(1L); task.setStatus("PROCESSING");
        when(submit.submit(any())).thenReturn(task);
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
    }
    ApiVideoService.CreateContext context(String id, String prompt, Integer duration) {
        return new ApiVideoService.CreateContext(key, id, "1.1.1.1", "test", prompt, "model",
                List.of(), List.of(), List.of(), duration, "16:9", null);
    }
    ApiCallLog firstRequest() {
        api.create(context("identity", "prompt", 5));
        var captured = ArgumentCaptor.forClass(ApiCallLog.class);
        verify(logs).insert(captured.capture());
        return captured.getValue();
    }

    // 【测什么】非法Unicode在查询日志、素材或提交前返回400，不能按替换字符重放旧任务。
    // 【怎么算红】先重放再校验或静默替换代理字符会违反异常/零调用断言。
    @Test void invalidUnicodeRejectedBeforeIdentityLookup() {
        var error = assertThrows(ApiException.class, () -> api.create(context("identity", "p\uD800", 5)));
        assertEquals(400, error.getHttpStatus().value());
        verifyNoInteractions(logs, registry, storage, submit, tasks);
    }

    // 【测什么】新日志保存64位指纹，相同请求重放不再解析模型/提交，关模型也不影响。
    // 【怎么算红】不写指纹或重放重新进入模型闸门，本测试失败。
    @Test void sameRequestReplaysWithPersistedFingerprintBeforeModelGate() throws Exception {
        var log = firstRequest();
        assertNotNull(log.getRequestFingerprint());
        assertTrue(log.getRequestFingerprint().matches("[a-f0-9]{64}"));
        when(logs.selectOne(any())).thenReturn(log);
        clearInvocations(submit, registry, storage);
        assertEquals("tsk_identity", api.create(context("identity", " prompt ", 5)).businessTaskId());
        verifyNoInteractions(registry, storage);
        verify(submit, never()).submit(any());
    }

    // 【测什么】同key换prompt或duration、显式duration变省略都409，无任何媒体/提交副作用。
    // 【怎么算红】删除指纹比较会返回原任务而不是IDE MPOTENCY冲突。
    @Test void changedRequestIs409BeforeAnySideEffects() throws Exception {
        var log = firstRequest(); when(logs.selectOne(any())).thenReturn(log);
        clearInvocations(submit, registry, storage);
        for (var request : List.of(context("identity", "changed", 5), context("identity", "prompt", 8),
                context("identity", "prompt", null))) {
            var error = assertThrows(ApiException.class, () -> api.create(request));
            assertEquals("IDEMPOTENCY_KEY_REUSED", error.getCode());
            assertEquals(409, error.getHttpStatus().value());
        }
        verifyNoInteractions(registry, storage);
        verify(submit, never()).submit(any());
    }

    // 【测什么】旧无指纹日志保留恢复，不拿新请求冒充原始参数回填。
    // 【怎么算红】拒绝旧记录或回填新指纹，原任务恢复/空指纹断言失败。
    @Test void legacyRowsStillReplayWithoutBackfillingIdentity() {
        var log = new ApiCallLog(); log.setApiKeyId(1L); log.setTaskId("tsk_identity");
        when(logs.selectOne(any())).thenReturn(log);
        assertEquals("tsk_identity", api.create(context("old", "prompt", 5)).businessTaskId());
        assertNull(log.getRequestFingerprint());
        verifyNoInteractions(registry, storage);
    }

    // 【测什么】64字符可提交，65字符先400，不能超过数据库列宽才失败。
    // 【怎么算红】保留124上限，65字符请求会成功而不是400。
    @Test void externalKeyMatchesDatabaseWidth() {
        assertNotNull(api.create(context("a".repeat(64), "prompt", 5)));
        var error = assertThrows(ApiException.class, () -> api.create(context("a".repeat(65), "prompt", 5)));
        assertEquals("VALIDATION_ERROR", error.getCode());
    }

    // 【测什么】并发同key不同prompt在唯一键争抢后仍比较赢家指纹，不能误认赢家任务。
    // 【怎么算红】只在快路径比较指纹，两个线程中输家会恢复赢家任务而不是409。
    @Test void concurrentDifferentPayloadsConflictAfterUniqueClaim() throws Exception {
        reset(logs, tasks, submit);
        var claimed = new java.util.concurrent.atomic.AtomicReference<ApiCallLog>();
        var bothAtInsert = new java.util.concurrent.CyclicBarrier(2);
        when(logs.selectOne(any())).thenAnswer(inv -> claimed.get());
        when(logs.insert(any(ApiCallLog.class))).thenAnswer(inv -> {
            ApiCallLog candidate = inv.getArgument(0);
            bothAtInsert.await(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!claimed.compareAndSet(null, candidate)) throw new org.springframework.dao.DuplicateKeyException("race");
            candidate.setId(9L);
            return 1;
        });
        when(logs.linkTaskByRequestId(any(), any(), anyString(), anyString(), any())).thenReturn(1);
        var task = new VideoTask(); task.setBizTaskId("tsk_winner"); task.setStatus("PROCESSING"); task.setApiKeyId(1L);
        when(submit.submit(any())).thenReturn(task);
        when(submit.findByRequestId(any(), any())).thenReturn(task);
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(() -> createOrError(context("race", "left", 5)));
            var right = pool.submit(() -> createOrError(context("race", "right", 5)));
            var results = List.of(left.get(10, java.util.concurrent.TimeUnit.SECONDS), right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(v -> v == task).count());
            assertEquals(1, results.stream().filter(v -> v instanceof ApiException e
                    && "IDEMPOTENCY_KEY_REUSED".equals(e.getCode())).count());
            verify(submit, times(1)).submit(any());
            verifyNoInteractions(storage);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private Object createOrError(ApiVideoService.CreateContext context) {
        try { return api.create(context); } catch (ApiException e) { return e; }
    }
}
