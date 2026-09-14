package org.example.seedancegenarate.service;

import com.aliyun.oss.OSS;
import org.example.seedancegenarate.config.OssConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiReferenceStorageTest {
    private final OSS oss = mock(OSS.class);
    private final OssConfig config = config();
    private final ApiReferenceStorage storage = new ApiReferenceStorage(config, oss);

    // 【测什么】相同内容重试仍有不同对象，上传字节、URL和删除handle准确对应。
    // 【怎么算红】改为内容hash键、上传错误字节或删除另一个key，断言失败。
    @Test void repeatedContentHasIndependentExactHandles() throws Exception {
        byte[] bytes = {1, 2, 3};
        var first = storage.upload(bytes, ".PNG");
        var second = storage.upload(bytes, "png");
        assertNotEquals(first.objectKey(), second.objectKey());
        assertTrue(first.objectKey().matches("api-references/[0-9a-f-]{36}\\.png"));
        assertEquals("https://cdn.example.test/" + first.objectKey(), first.url());
        var input = ArgumentCaptor.forClass(InputStream.class);
        verify(oss).putObject(eq("bucket"), eq(first.objectKey()), input.capture());
        assertArrayEquals(bytes, input.getValue().readAllBytes());
        verify(oss).putObject(eq("bucket"), eq(second.objectKey()), any(InputStream.class));
        storage.delete(first);
        verify(oss).deleteObject("bucket", first.objectKey());
        verifyNoMoreInteractions(oss);
    }

    // 【测什么】null/空字节及路径、URL、空扩展名在任何OSS调用前拒绝。
    // 【怎么算红】去掉输入守卫或把路径静默清洗成扩展名，assertThrows失败。
    @Test void invalidUploadInputsHaveNoSideEffects() {
        assertThrows(IllegalArgumentException.class, () -> storage.upload(null, "png"));
        assertThrows(IllegalArgumentException.class, () -> storage.upload(new byte[0], "png"));
        for (String extension : new String[]{null, "", ".", "../png", "a.png", " png", "png/", "https://x"}) {
            assertThrows(IllegalArgumentException.class, () -> storage.upload(new byte[]{1}, extension));
        }
        verifyNoInteractions(oss);
    }

    // 【测什么】handle不可公开构造；共享key、任意URL、伪前缀和路径穿越均不可删除。
    // 【怎么算红】构造器改public或删除完整key格式校验，断言失败。
    @Test void deleteRejectsForeignAndMalformedKeys() throws Exception {
        var constructor = ApiReferenceStorage.OwnedReference.class.getDeclaredConstructor(String.class, String.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(IllegalArgumentException.class, () -> storage.delete(null));
        for (String key : new String[]{null, "images/abc.png", "https://cdn.example.test/images/abc.png",
                "api-references/../images/abc.png", "api-references/not-a-uuid.png",
                "api-references-other/00000000-0000-4000-8000-000000000000.png",
                "api-references/00000000-0000-4000-8000-000000000000.png/../x",
                "api-references/00000000-0000-4000-8000-000000000000.png\n"}) {
            var forged = constructor.newInstance("https://cdn.example.test/ignored", key);
            assertThrows(IllegalArgumentException.class, () -> storage.delete(forged));
        }
        verifyNoInteractions(oss);
    }

    // 【测什么】上传抛异常后仅补偿这次key，返回同一个原异常。
    // 【怎么算红】不补偿、删不同key或包装异常，断言失败。
    @Test void failedUploadCleansItsExactKeyAndRethrowsOriginal() {
        RuntimeException failure = new RuntimeException("put failed");
        when(oss.putObject(eq("bucket"), anyString(), any(InputStream.class))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> storage.upload(new byte[]{1}, "mp4")));
        var key = ArgumentCaptor.forClass(String.class);
        var order = inOrder(oss);
        order.verify(oss).putObject(eq("bucket"), key.capture(), any(InputStream.class));
        order.verify(oss).deleteObject("bucket", key.getValue());
        assertTrue(key.getValue().startsWith("api-references/"));
        verifyNoMoreInteractions(oss);
    }

    // 【测什么】补偿也失败时仍抛原上传错误，清理错误作为suppressed保留。
    // 【怎么算红】让清理异常覆盖上传异常或吞掉清理错误，断言失败。
    @Test void cleanupFailureIsSuppressedOnOriginalUploadFailure() {
        RuntimeException failure = new RuntimeException("put failed");
        RuntimeException cleanup = new RuntimeException("delete failed");
        when(oss.putObject(eq("bucket"), anyString(), any(InputStream.class))).thenThrow(failure);
        doThrow(cleanup).when(oss).deleteObject(eq("bucket"), anyString());
        assertSame(failure, assertThrows(RuntimeException.class, () -> storage.upload(new byte[]{1}, "mp3")));
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
    }

    // 【测什么】SDK复用同一异常实例时，不因自我suppressed丢掉原异常。
    // 【怎么算红】无条件addSuppressed，抛出IllegalArgumentException导致失败。
    @Test void identicalCleanupExceptionDoesNotReplaceOriginal() {
        RuntimeException failure = new RuntimeException("same failure");
        when(oss.putObject(eq("bucket"), anyString(), any(InputStream.class))).thenThrow(failure);
        doThrow(failure).when(oss).deleteObject(eq("bucket"), anyString());
        assertSame(failure, assertThrows(RuntimeException.class, () -> storage.upload(new byte[]{1}, "wav")));
    }

    // 【测什么】显式删除的SDK异常交回调用方，不冒称已清理。
    // 【怎么算红】delete吞异常，assertThrows失败。
    @Test void explicitDeletePropagatesFailure() {
        var owned = storage.upload(new byte[]{1}, "jpg");
        RuntimeException failure = new RuntimeException("delete failed");
        doThrow(failure).when(oss).deleteObject("bucket", owned.objectKey());
        assertSame(failure, assertThrows(RuntimeException.class, () -> storage.delete(owned)));
    }

    // 【测什么】裸域名、完整HTTP域名、空域名与endpoint的处理沿用现有存储。
    // 【怎么算红】丢scheme、保留尾斜杠或不去endpoint协议头，URL断言失败。
    @Test void domainResolutionMatchesExistingStorage() {
        for (String[] pair : new String[][]{
                {" cdn.example.test/// ", "https://cdn.example.test"},
                {"http://cdn.example.test/", "http://cdn.example.test"},
                {"https://cdn.example.test/", "https://cdn.example.test"},
                {" ", "https://bucket.oss.example.test"},
                {null, "https://bucket.oss.example.test"}}) {
            config.setDomain(pair[0]);
            var owned = storage.upload(new byte[]{1}, "png");
            assertEquals(pair[1] + "/" + owned.objectKey(), owned.url());
        }
    }

    private static OssConfig config() {
        var config = new OssConfig();
        config.setBucketName("bucket");
        config.setEndpoint("https://oss.example.test");
        config.setDomain("cdn.example.test");
        return config;
    }
}
