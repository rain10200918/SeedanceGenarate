package org.example.seedancegenarate.service.Impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PolicyConditions;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlRequest;
import org.example.seedancegenarate.dto.ApiAssetUploadUrlResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApiAssetUploadServiceImplTest {
    private OSS ossClient;

    private OssConfig config;
    private ApiAssetUploadServiceImpl service;
    private AtomicReference<PolicyConditions> capturedConditions;
    private AtomicInteger sdkCalls;

    @BeforeEach
    void setUp() {
        config = new OssConfig();
        config.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        config.setBucketName("test-bucket");
        config.setAccessKeyId("visible-key-id");
        config.setAccessKeySecret("must-never-leak");
        capturedConditions = new AtomicReference<>();
        sdkCalls = new AtomicInteger();
        ossClient = (OSS) Proxy.newProxyInstance(OSS.class.getClassLoader(), new Class<?>[]{OSS.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    sdkCalls.incrementAndGet();
                    return switch (method.getName()) {
                        case "generatePostPolicy" -> {
                            capturedConditions.set((PolicyConditions) args[1]);
                            yield "{\"expiration\":\"soon\",\"conditions\":[]}";
                        }
                        case "calculatePostSignature" -> "signed-policy";
                        case "generatePresignedUrl" ->
                                new URL("http://test-bucket.oss-cn-beijing.aliyuncs.com/object?signature=x");
                        default -> throw new AssertionError("直传凭证不应调用 OSS." + method.getName());
                    };
                });
        service = new ApiAssetUploadServiceImpl(ossClient, config);
    }

    @Test
    @DisplayName("API 直传凭证: 限定属主目录、MIME 和声明文件大小")
    void issuesOwnerScopedBoundedPostPolicyWithoutSecret() throws Exception {
        // 【测什么】签名策略绑定一个随机对象 key、精确 MIME 和精确文件大小。
        ApiAssetUploadUrlResponse result = service.issue(42L,
                new ApiAssetUploadUrlRequest("portrait.png", "image/png", 1234L));

        assertEquals("POST", result.method());
        assertEquals("https://test-bucket.oss-cn-beijing.aliyuncs.com", result.uploadUrl());
        assertTrue(result.fields().get("key").matches("api-uploads/42/[a-f0-9]{32}\\.png"));
        assertEquals("image/png", result.fields().get("Content-Type"));
        assertEquals("204", result.fields().get("success_action_status"));
        assertEquals("visible-key-id", result.fields().get("OSSAccessKeyId"));
        assertEquals("signed-policy", result.fields().get("Signature"));
        assertEquals("{\"expiration\":\"soon\",\"conditions\":[]}",
                new String(Base64.getDecoder().decode(result.fields().get("policy")), StandardCharsets.UTF_8));
        assertTrue(result.assetUrl().startsWith("https://"));

        String policyConditions = capturedConditions.get().jsonize();
        assertTrue(policyConditions.contains("\"key\":\""
                + result.fields().get("key").replace("/", "\\/")));
        assertTrue(policyConditions.contains("\"Content-Type\":\"image\\/png"));
        assertTrue(policyConditions.contains("[\"content-length-range\",1234,1234]"));
        // 【怎么算红】响应中出现 AccessKeySecret，或策略能改 key/MIME/大小，即凭证可被越权滥用。
        assertFalse(result.toString().contains(config.getAccessKeySecret()));
    }

    @Test
    @DisplayName("API 直传凭证: 拒绝超限和非图片文件")
    void rejectsInvalidUploadBeforeSigning() {
        // 【测什么】客户端不能绕过 P0 图片白名单和 30 MiB 上限。
        config.setApiUploadMaxBytes(100L * 1024 * 1024);
        ApiException wrongType = assertThrows(ApiException.class, () -> service.issue(42L,
                new ApiAssetUploadUrlRequest("payload.exe", "application/octet-stream", 100L)));
        ApiException tooLarge = assertThrows(ApiException.class, () -> service.issue(42L,
                new ApiAssetUploadUrlRequest("huge.png", "image/png", 30L * 1024 * 1024 + 1)));

        assertEquals("VALIDATION_ERROR", wrongType.getCode());
        assertEquals("VALIDATION_ERROR", tooLarge.getCode());
        // 【怎么算红】非法请求触发任何 OSS SDK 调用，说明校验过晚。
        assertEquals(0, sdkCalls.get());
    }
}
