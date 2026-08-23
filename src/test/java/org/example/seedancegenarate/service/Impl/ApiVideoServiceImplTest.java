package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.OssService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.IpUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiVideoServiceImplTest {

    @Mock
    private ApiCallLogMapper apiCallLogMapper;
    @Mock
    private VideoSubmitService videoSubmitService;
    @Mock
    private VideoEngineRegistry videoEngineRegistry;
    @Mock
    private OssService ossService;
    @Mock
    private VideoTaskService videoTaskService;
    @Mock
    private VideoEngine videoEngine;

    private ApiVideoServiceImpl apiVideoService;

    @BeforeEach
    void setUp() {
        apiVideoService = new ApiVideoServiceImpl(
                apiCallLogMapper, videoSubmitService, videoEngineRegistry, ossService, videoTaskService);
    }

    @Test
    @DisplayName("IpUtils: 识别私有/回环/本地 IP 为私有地址")
    void testIsPrivateOrLocalAddress() throws Exception {
        assertTrue(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("172.16.0.1")));
        assertTrue(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(IpUtils.isPrivateOrLocalAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    @DisplayName("API 创建生成任务: 传入内网参考图地址被 SSRF 拦截")
    void testSsrfProtectionRejectsLocalUrl() {
        when(videoEngineRegistry.all()).thenReturn(List.of(videoEngine));
        ModelSpec spec = new ModelSpec("test-provider", "test-model", "测试模型", false, 0, 1, List.of("16:9"), 5, 10, List.of(5), OutputType.VIDEO);
        when(videoEngine.models()).thenReturn(List.of(spec));
        when(videoEngine.provider()).thenReturn("test-provider");

        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setUserId(100L);

        ApiVideoService.CreateContext context = new ApiVideoService.CreateContext(
                key, "req_1", "127.0.0.1", "TestAgent",
                "test prompt", "test-model", List.of("http://127.0.0.1:8080/secret.png"),
                5, "16:9", null);

        ApiException ex = assertThrows(ApiException.class, () -> apiVideoService.create(context));
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("禁止使用内网或本地参考图地址") || ex.getMessage().contains("参考图"));
    }

    @Test
    @DisplayName("API 创建生成任务: 余额不足时抛出 INSUFFICIENT_BALANCE (402)")
    void testInsufficientBalanceMapping() throws Exception {
        when(videoEngineRegistry.all()).thenReturn(List.of(videoEngine));
        ModelSpec spec = new ModelSpec("test-provider", "test-model", "测试模型", false, 0, 1, List.of("16:9"), 5, 10, List.of(5), OutputType.VIDEO);
        when(videoEngine.models()).thenReturn(List.of(spec));
        when(videoEngine.provider()).thenReturn("test-provider");

        when(videoSubmitService.submit(any())).thenThrow(new WalletServiceImpl.InsufficientBalanceException());

        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setUserId(100L);

        ApiVideoService.CreateContext context = new ApiVideoService.CreateContext(
                key, "req_1", "1.1.1.1", "TestAgent",
                "test prompt", "test-model", List.of(),
                5, "16:9", null);

        ApiException ex = assertThrows(ApiException.class, () -> apiVideoService.create(context));
        assertEquals("INSUFFICIENT_BALANCE", ex.getCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("余额不足"));
    }
}
