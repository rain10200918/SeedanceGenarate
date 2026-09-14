package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.ApiVideoCreateRequest;
import org.example.seedancegenarate.dto.ApiVideoCreateResponse;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.ArtifactExpiryPolicy;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.ContentModerationPolicy;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiVideoControllerTest {
    @Mock
    private ApiVideoService apiVideoService;
    @Mock
    private VideoTaskService videoTaskService;
    @Mock
    private ApiDocService apiDocService;
    @Mock
    private ArtifactStorage artifactStorage;
    @Mock
    private OssConfig ossConfig;
    @Mock
    private ArtifactExpiryPolicy artifactExpiryPolicy;
    @Mock
    private ContentModerationPolicy contentModerationPolicy;

    // 【测什么】显式空白、超长、控制字符、FORMAT与Unicode行段分隔符均在服务调用前拒绝。
    // 【怎么算红】恢复 blank 随机键、删长度校验或先 trim 再查控制字符时异常或零调用断言失败。
    @Test
    void invalidExplicitIdempotencyKeysNeverCallService() {
        ApiVideoController controller = controller();
        HttpServletRequest request = authenticatedRequest();
        for (String key : List.of("", "   ", "\u2003", "x".repeat(65), "  " + "x".repeat(65) + "  ",
                "\tvalid", "valid\n", "a\rb", "a\u0000b", "a\u007fb", "a\u0085b",
                "a\u200bb", "\ufeffvalid", "a\u202eb", "a\u2028b", "a\u2029b",
                "a" + new String(Character.toChars(0xE0001)) + "b")) {
            ApiException error = assertThrows(ApiException.class,
                    () -> controller.create(createBody(), key, request));
            assertEquals(400, error.getHttpStatus().value());
            assertEquals("VALIDATION_ERROR", error.getCode());
        }
        verifyNoInteractions(apiVideoService, videoTaskService, artifactStorage);
    }

    // 【测什么】缺失头仍生成合法请求身份，响应与服务使用同一个键。
    // 【怎么算红】把缺失视为非法或响应与服务使用不同随机键时断言失败。
    @Test
    void missingIdempotencyKeyStillGeneratesIdentity() {
        stubCreatedTask();
        var response = controller().create(createBody(), null, authenticatedRequest());
        var capture = ArgumentCaptor.forClass(ApiVideoService.CreateContext.class);
        verify(apiVideoService).create(capture.capture());
        String generated = capture.getValue().requestId();
        assertTrue(generated != null && !generated.isBlank() && generated.length() <= 64);
        assertEquals(generated, response.getBody().requestId());
        assertEquals(202, response.getStatusCode().value());
    }

    // 【测什么】合法键只 trim，64字符边界可用，原始头含空格超过64不误拒。
    // 【怎么算红】按原始头长度限制、重新生成或改变键正文时服务参数断言失败。
    @Test
    void validIdempotencyKeyKeepsTrimmedIdentity() {
        stubCreatedTask();
        String identity = "k".repeat(64);
        var response = controller().create(createBody(), "  " + identity + "  ", authenticatedRequest());
        var capture = ArgumentCaptor.forClass(ApiVideoService.CreateContext.class);
        verify(apiVideoService).create(capture.capture());
        assertEquals(identity, capture.getValue().requestId());
        assertEquals(identity, response.getBody().requestId());
    }

    private ApiVideoController controller() {
        return new ApiVideoController(apiVideoService, videoTaskService, apiDocService, artifactStorage,
                ossConfig, artifactExpiryPolicy, contentModerationPolicy);
    }

    private HttpServletRequest authenticatedRequest() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        ApiKey key = new ApiKey();
        key.setId(7L);
        request.setAttribute("api_key", key);
        return request;
    }

    private ApiVideoCreateRequest createBody() {
        return new ApiVideoCreateRequest("prompt", "model", null, null, null, 8, null, null);
    }

    private void stubCreatedTask() {
        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk_identity");
        task.setStatus("PROCESSING");
        when(apiVideoService.create(any())).thenReturn(task);
    }

    @Test
    void createForwardsVideoAndAudioReferenceUrls() {
        // 【测什么】对外 JSON 请求中的 videos/audios 原样进入 CreateContext，而不是只停留在 DTO。
        // 【怎么算红】Controller 漏传任一列表时，API 虽能返回 202，但共享生成链路永远收不到对应参考素材。
        ApiVideoController controller = new ApiVideoController(
                apiVideoService, videoTaskService, apiDocService, artifactStorage, ossConfig,
                artifactExpiryPolicy, contentModerationPolicy);
        ApiKey key = new ApiKey();
        key.setId(7L);
        key.setUserId(42L);
        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk_api_media");
        task.setStatus("PROCESSING");
        when(apiVideoService.create(any())).thenReturn(task);

        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getAttribute("api_key")).thenReturn(key);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("User-Agent")).thenReturn("api-test");

        ApiVideoCreateRequest body = new ApiVideoCreateRequest(
                "参考视频的镜头运动，参考音频的人声质感",
                "minimax-h3-4step",
                List.of("https://media.example.com/subject.png"),
                List.of("https://media.example.com/motion.mp4"),
                List.of("https://media.example.com/voice.mp3"),
                6, "16:9", null);

        ResponseEntity<ApiVideoCreateResponse> response = controller.create(body, "idem-media", request);

        ArgumentCaptor<ApiVideoService.CreateContext> captured =
                ArgumentCaptor.forClass(ApiVideoService.CreateContext.class);
        verify(apiVideoService).create(captured.capture());
        assertEquals(List.of("https://media.example.com/subject.png"), captured.getValue().imageUrls());
        assertEquals(List.of("https://media.example.com/motion.mp4"), captured.getValue().videoUrls());
        assertEquals(List.of("https://media.example.com/voice.mp3"), captured.getValue().audioUrls());
        assertEquals("tsk_api_media", response.getBody().taskId());
    }

    @Test
    void jsonRequestBindsVideoAndAudioLists() throws Exception {
        // 【测什么】真实 application/json 字段名 videos/audios 能绑定到请求 DTO。
        // 【怎么算红】只改了 Java 构造器或内部上下文但记录组件名不匹配时，外部 JSON 会静默丢失参考素材。
        ApiVideoCreateRequest request = new ObjectMapper().readValue("""
                {
                  "prompt": "media",
                  "model": "minimax-h3-4step",
                  "videos": ["https://media.example.com/a.mp4"],
                  "audios": ["https://media.example.com/b.mp3"],
                  "duration": 6
                }
                """, ApiVideoCreateRequest.class);

        assertEquals(List.of("https://media.example.com/a.mp4"), request.videos());
        assertEquals(List.of("https://media.example.com/b.mp3"), request.audios());
    }
}
