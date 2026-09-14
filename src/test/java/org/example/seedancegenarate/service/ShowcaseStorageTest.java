package org.example.seedancegenarate.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.example.seedancegenarate.config.OssConfig;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShowcaseStorageTest {
    // 【测什么】PUT请求本身携带私有ACL、长度与类型，并把流直接传SDK。
    // 【怎么算红】去掉ACL或改用不带ACL的上传重载，此断言失败。
    @Test void privateStreamingPut() {
        OSS oss = mock(OSS.class);
        var config = new OssConfig(); config.setBucketName("bucket");
        var input = new ByteArrayInputStream(new byte[]{1,2,3});
        new ShowcaseStorage(oss, config).put("showcase/1/request/hash/media.png", input, "image/png", 3);
        var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(oss).putObject(request.capture());
        assertSame(input, request.getValue().getInputStream());
        assertEquals("bucket", request.getValue().getBucketName());
        assertEquals("private", request.getValue().getMetadata().getRawMetadata().get("x-oss-object-acl"));
        assertEquals(3, request.getValue().getMetadata().getContentLength());
        assertEquals("image/png", request.getValue().getMetadata().getContentType());
    }
    // 【测什么】签名固定60秒和no-store，不继承outputs的TTL或前缀。
    // 【怎么算红】用配置300秒签名或允许outputs key会失败。
    @Test void shortSignatureAndPrefix() throws Exception {
        OSS oss = mock(OSS.class);
        var config = new OssConfig(); config.setBucketName("bucket"); config.setSignedUrlTtlSeconds(300);
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(URI.create("https://example.test/media?signature=example").toURL());
        var storage = new ShowcaseStorage(oss, config);
        long before = System.currentTimeMillis();
        assertEquals("https", storage.sign("showcase/1/request/hash/media.mp4").getScheme());
        long after = System.currentTimeMillis();
        var request = org.mockito.ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(oss).generatePresignedUrl(request.capture());
        long expires = request.getValue().getExpiration().getTime();
        assertTrue(expires >= before + 60000 && expires <= after + 60000);
        assertEquals("no-store", request.getValue().getResponseHeaders().getCacheControl());
        assertThrows(IllegalArgumentException.class, () -> storage.sign("outputs/x.mp4"));
        assertThrows(IllegalArgumentException.class, () -> storage.put("showcase/../x", null, "image/png", 1));
        verifyNoMoreInteractions(oss);
    }
}
