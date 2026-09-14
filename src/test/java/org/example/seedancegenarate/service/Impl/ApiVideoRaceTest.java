package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.*;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiVideoRaceTest {
    // 【测什么】两个真实线程在上传后同时争抢同幂等键，只提交一次，输家只删除自己的对象。
    // 【怎么算红】移除日志争抢分支或清理赢家对象，submit次数或引用身份断言失败。
    @Test void concurrentSameKeyNeverDeletesWinningReferences() throws Exception {
        var logs = mock(ApiCallLogMapper.class);
        var submit = mock(VideoSubmitService.class);
        var storage = mock(ApiReferenceStorage.class);
        var engine = mock(VideoEngine.class);
        when(engine.provider()).thenReturn("test");
        when(engine.models()).thenReturn(List.of(new ModelSpec("test", "model", "Model", false,
                0, 1, List.of("16:9"), 5, 5, List.of(5))));
        var api = spy(new ApiVideoServiceImpl(logs, submit, new VideoEngineRegistry(List.of(engine)),
                storage, mock(VideoTaskService.class)));
        doAnswer(invocation -> {
            var connection = mock(HttpURLConnection.class);
            when(connection.getResponseCode()).thenReturn(200);
            when(connection.getContentLengthLong()).thenReturn(1L);
            when(connection.getContentType()).thenReturn("image/png");
            when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
            return connection;
        }).when(api).openReferenceConnection(any());

        var bothUploaded = new CyclicBarrier(2);
        when(storage.upload(any(), eq(".png"))).thenAnswer(invocation -> {
            var handle = mock(ApiReferenceStorage.OwnedReference.class);
            when(handle.url()).thenReturn("https://oss.test/api-references/" + UUID.randomUUID() + ".png");
            bothUploaded.await(5, TimeUnit.SECONDS);
            return handle;
        });
        var claims = new AtomicInteger();
        when(logs.insert(any(ApiCallLog.class))).thenAnswer(invocation -> {
            if (claims.incrementAndGet() != 1) throw new DuplicateKeyException("unique requestId");
            ((ApiCallLog) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        when(logs.linkTaskByRequestId(any(), any(), anyString(), anyString(), any())).thenReturn(1);
        var task = new VideoTask(); task.setBizTaskId("tsk_winner"); task.setStatus("PROCESSING");
        when(submit.submit(any())).thenReturn(task);
        var key = new ApiKey(); key.setId(1L); key.setUserId(2L);
        var context = new ApiVideoService.CreateContext(key, "race", "1.1.1.1", "test", "prompt", "model",
                List.of("https://8.8.8.8/ref.png"), 5, "16:9", null);
        Callable<Object> create = () -> {
            try { return api.create(context); } catch (ApiException e) { return e; }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var left = pool.submit(create);
            var right = pool.submit(create);
            var results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> value == task).count());
            assertEquals(1, results.stream().filter(value -> value instanceof ApiException error
                    && "REQUEST_IN_PROGRESS".equals(error.getCode())).count());
            var submitted = ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
            var deleted = ArgumentCaptor.forClass(ApiReferenceStorage.OwnedReference.class);
            verify(submit, times(1)).submit(submitted.capture());
            verify(storage, times(1)).delete(deleted.capture());
            verify(storage, times(2)).upload(any(), eq(".png"));
            assertFalse(submitted.getValue().imageUrls().contains(deleted.getValue().url()),
                    "loser must not delete winner's reference");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
