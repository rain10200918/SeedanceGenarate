package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiTaskDeliveryControllerTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private VideoTaskService tasks;
    private ArtifactStorage storage;
    private ApiVideoController controller;
    private MockHttpServletRequest request;
    private VideoTask task;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), VideoTask.class);
        tasks = mock(VideoTaskService.class);
        storage = mock(ArtifactStorage.class);
        controller = new ApiVideoController(mock(ApiVideoService.class), tasks, mock(ApiDocService.class),
                storage, new OssConfig(), new ArtifactExpiryPolicy(30), new ContentModerationPolicy());
        request = new MockHttpServletRequest();
        ApiKey key = new ApiKey();
        key.setId(7L);
        request.setAttribute("api_key", key);
        task = new VideoTask();
        task.setId(99L);
        task.setApiKeyId(7L);
        task.setUserId(42L);
        task.setBizTaskId("tsk_public");
        task.setTaskId("provider-private");
        task.setStatus("SUCCESS");
        task.setModel("music");
        task.setOutputType("AUDIO");
        task.setDuration(120);
        task.setCostAmount(new BigDecimal("1.25"));
        task.setFreezeAmount(new BigDecimal("2.50"));
        task.setVideoUrl("data/videos/tsk_public.mp3");
        task.setImages("https://private.example/ref?key=secret");
        task.setNodeId("private-node");
        task.setCreateTime(LocalDateTime.now());
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
    }

    // 【测什么】详情与分页实际序列化只保留合同字段，公开任务身份与费用不丢失。
    // 【怎么算红】任一入口直接返回实体、漏字段或保留原始错误时，字段集合或敏感串断言失败。
    @Test
    void detailAndPageHaveOnlyPublicFields() throws Exception {
        task.setErrorMsg("GET https://private.example/?key=secret; SELECT password FROM users");
        JsonNode detail = mapper.valueToTree(controller.get("tsk_public", request));
        Set<String> expected = Set.of("taskId", "status", "model", "costAmount", "videoUrl",
                "artifactExpired", "errorMsg", "outputType", "duration", "ratio", "processingState",
                "processingMessage", "moderationStatus", "moderationReasonCode", "moderationMessage");
        assertEquals(expected, fields(detail));
        assertEquals("tsk_public", detail.path("taskId").asText());
        assertEquals("tsk_public.mp3", detail.path("videoUrl").asText());
        assertEquals("AUDIO", detail.path("outputType").asText());
        assertEquals(120, detail.path("duration").asInt());
        assertEquals(0, new BigDecimal("1.25").compareTo(detail.path("costAmount").decimalValue()));
        assertFalse(detail.path("errorMsg").asText().isBlank());
        for (String secret : List.of("private.example", "secret", "SELECT", "password", "private-node")) {
            assertFalse(detail.toString().contains(secret));
        }
        Page<VideoTask> page = new Page<>(2, 10, 21);
        page.setRecords(List.of(task));
        when(tasks.page(any(Page.class), any())).thenReturn(page);
        JsonNode result = mapper.valueToTree(controller.list(2, 10, request));
        assertEquals(Set.of("records", "total", "size", "current", "pages"), fields(result));
        assertEquals(expected, fields(result.path("records").get(0)));
        assertEquals(detail, result.path("records").get(0));
        assertEquals(21, result.path("total").asInt());
        assertEquals(2, result.path("current").asInt());
        assertEquals(10, result.path("size").asInt());
        assertEquals(3, result.path("pages").asInt());
    }

    // 【测什么】审核、过期和恢复中状态保留公开语义，审核不可被错误脱敏抹掉。
    // 【怎么算红】漏过期打标、漏屏蔽裁剪或用通用错误覆盖公开审核说明时断言失败。
    @Test
    void moderationExpiryAndRecoveryRemainVisible() {
        task.setArtifactStorageType("OSS");
        task.setCreateTime(LocalDateTime.now().minusDays(31));
        task.setModerationStatus("BLOCKED");
        task.setModerationReasonCode("POLICY");
        task.setModerationMessage("此内容暂不可下载，可联系平台申诉。");
        JsonNode blocked = mapper.valueToTree(controller.get("tsk_public", request));
        assertTrue(blocked.path("artifactExpired").asBoolean());
        assertTrue(blocked.path("videoUrl").isNull());
        assertEquals("SUCCESS", blocked.path("status").asText());
        assertEquals("BLOCKED", blocked.path("moderationStatus").asText());
        assertEquals("POLICY", blocked.path("moderationReasonCode").asText());
        assertEquals(task.getModerationMessage(), blocked.path("moderationMessage").asText());
        task.setStatus("PROCESSING");
        task.setPhase("RECOVERY_REQUIRED");
        JsonNode recovering = mapper.valueToTree(controller.get("tsk_public", request));
        assertEquals("RECOVERING", recovering.path("processingState").asText());
        assertEquals(task.processingMessage(), recovering.path("processingMessage").asText());
    }

    // 【测什么】外部地址、对象路径和穿越标识不进入公开 videoUrl。
    // 【怎么算红】直接透传实体 videoUrl 或仅截 URL 最后一段时断言失败。
    @ParameterizedTest
    @ValueSource(strings = {"https://host/a.mp3?key=secret", "outputs/private/a.mp3", "../a.mp3",
            "data/videos/../a.mp3", "/tmp/a.mp3", "a\\b.mp3", "a.mp3?key=secret"})
    void unsafeArtifactIdentifiersAreNotPublic(String stored) {
        task.setVideoUrl(stored);
        JsonNode result = mapper.valueToTree(controller.get("tsk_public", request));
        assertTrue(result.path("videoUrl").isNull());
    }

    // 【测什么】仅临时目录的音频文件通过实际下载入口返回正确字节和 MIME。
    // 【怎么算红】缺任一音频映射或未知类型仍回落 MP4 时断言失败。
    @ParameterizedTest
    @CsvSource({"mp3,audio/mpeg", "M4A,audio/mp4", "aac,audio/aac", "wav,audio/wav",
            "ogg,audio/ogg", "flac,audio/flac", "unknown,application/octet-stream",
            "mp4,video/mp4", "png,image/png"})
    void downloadsTemporaryMediaWithCorrectMime(String extension, String mime) throws Exception {
        ReflectionTestUtils.setField(controller, "localArtifactRoot", temporary);
        byte[] bytes = {1, 3, 5, 7};
        String name = "tsk_public." + extension;
        Files.write(temporary.resolve(name), bytes);
        task.setVideoUrl("data/videos/" + name);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.content("tsk_public", request, response);
        assertEquals(mime, response.getContentType());
        assertArrayEquals(bytes, response.getContentAsByteArray());
        verifyNoInteractions(storage);
    }

    // 【测什么】本地目录穿越、绝对路径、非法字符及符号链接都不能读出边界外文件。
    // 【怎么算红】仅拼接路径或跟随符号链接时越界请求返回字节而不是安全拒绝。
    @Test
    void localDownloadRejectsEscapesAndNonFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.write(temporary.resolve("outside.mp3"), new byte[]{9});
        Files.createSymbolicLink(root.resolve("linked.mp3"), outside);
        Files.createDirectory(root.resolve("directory.mp3"));
        ReflectionTestUtils.setField(controller, "localArtifactRoot", root);
        for (String stored : List.of("../outside.mp3", outside.toString(), "data/videos/../outside.mp3",
                "a\\b.mp3", "bad\u0000.mp3", "linked.mp3", "directory.mp3", "missing.mp3")) {
            task.setVideoUrl(stored);
            MockHttpServletResponse response = new MockHttpServletResponse();
            ApiException error = assertThrows(ApiException.class,
                    () -> controller.content("tsk_public", request, response), stored);
            assertEquals(400, error.getHttpStatus().value());
            assertFalse(error.getMessage().contains(outside.toString()));
            assertEquals(0, response.getContentAsByteArray().length);
        }
        verifyNoInteractions(storage);
    }

    // 【测什么】不存在/异钥匙任务、屏蔽和过期仍在签名之前被拒绝。
    // 【怎么算红】绕过 findTask、屏蔽或过期守卫时异常码或零存储调用断言失败。
    @Test
    void accessGuardsRunBeforeSigning() throws Exception {
        when(tasks.getOne(any(), eq(false))).thenReturn(null);
        assertEquals("TASK_NOT_FOUND", assertThrows(ApiException.class,
                () -> controller.content("other", request, new MockHttpServletResponse())).getCode());
        assertEquals("TASK_NOT_FOUND", assertThrows(ApiException.class,
                () -> controller.get("other", request)).getCode());
        when(tasks.getOne(any(), eq(false))).thenReturn(task);
        task.setArtifactStorageType("OSS");
        task.setArtifactKey("outputs/private.mp3");
        task.setModerationStatus("BLOCKED");
        assertEquals("CONTENT_BLOCKED", assertThrows(ApiException.class,
                () -> controller.content("tsk_public", request, new MockHttpServletResponse())).getCode());
        task.setModerationStatus("VISIBLE");
        task.setCreateTime(LocalDateTime.now().minusDays(31));
        assertEquals("ARTIFACT_EXPIRED", assertThrows(ApiException.class,
                () -> controller.content("tsk_public", request, new MockHttpServletResponse())).getCode());
        verifyNoInteractions(storage);
    }

    // 【测什么】详情、下载与列表实际查询都携带当前钥匙约束；空分页保留形状。
    // 【怎么算红】删 apiKeyId 条件或把它并入任务 ID 的 OR 分支时 SQL 结构断言失败。
    @Test
    void allReadsAreScopedToTheCurrentKey() {
        when(tasks.getOne(any(), eq(false))).thenAnswer(invocation -> {
            LambdaQueryWrapper<VideoTask> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("api_key_id ="));
            assertTrue(sql.contains(") AND api_key_id ="), sql);
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            return task;
        });
        controller.get("tsk_public", request);
        task.setVideoUrl(null);
        assertThrows(ApiException.class,
                () -> controller.content("tsk_public", request, new MockHttpServletResponse()));
        when(tasks.page(any(Page.class), any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<VideoTask> query = invocation.getArgument(1);
            assertTrue(query.getSqlSegment().contains("api_key_id ="));
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            return invocation.getArgument(0);
        });
        JsonNode empty = mapper.valueToTree(controller.list(-1, 1000, request));
        assertEquals(0, empty.path("records").size());
        assertEquals(1, empty.path("current").asLong());
        assertEquals(100, empty.path("size").asLong());
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    // 【测什么】任务错误单独脱敏，空错误不编造失败，公开审核说明原样保留。
    // 【怎么算红】直接使用原始 errorMsg，或把审核说明也替换为通用文本时断言失败。
    @Test
    void errorTextIsSafeWithoutErasingPublicModeration() {
        task.setStatus("FAILED");
        task.setModerationMessage("请联系平台申诉。");
        for (String raw : List.of("https://internal/?token=secret", "key=secret", "SELECT password FROM users")) {
            task.setErrorMsg(raw);
            JsonNode result = mapper.valueToTree(controller.get("tsk_public", request));
            assertEquals("生成任务出现异常，请联系平台查询处理进度。", result.path("errorMsg").asText());
            assertEquals("请联系平台申诉。", result.path("moderationMessage").asText());
        }
        task.setErrorMsg(null);
        assertTrue(mapper.<JsonNode>valueToTree(controller.get("tsk_public", request)).path("errorMsg").isNull());
        task.setErrorMsg(" ");
        assertTrue(mapper.<JsonNode>valueToTree(controller.get("tsk_public", request)).path("errorMsg").isNull());
    }

    // 【测什么】AUDIO 仍经原路由签名重定向；假存储只返回占位地址、不联网。
    // 【怎么算红】AUDIO 被拒绝或改用文件名代替真实 artifactKey 签名时断言失败。
    @Test
    void ossAudioUsesExistingSignedRedirect() throws Exception {
        task.setArtifactStorageType("OSS");
        task.setArtifactKey("outputs/audio/tsk_public.mp3");
        when(storage.createSignedGetUrl(eq(task.getArtifactKey()), eq(java.time.Duration.ofSeconds(300))))
                .thenReturn("https://media.example.invalid/signed-audio");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.content("tsk_public", request, response);
        assertEquals(302, response.getStatus());
        assertEquals("https://media.example.invalid/signed-audio", response.getRedirectedUrl());
        verify(storage).createSignedGetUrl(task.getArtifactKey(), java.time.Duration.ofSeconds(300));
        verifyNoMoreInteractions(storage);
    }

    // 【测什么】真实 MVC 路由输出 AUDIO 白名单 JSON，不依赖手动序列化的假象。
    // 【怎么算红】路由丢失、实体重新泄漏或字段绑定变化时 HTTP 响应断言失败。
    @Test
    void originalHttpRouteReturnsSafeAudioJson() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/videos/tsk_public")
                        .requestAttr("api_key", request.getAttribute("api_key")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.taskId").value("tsk_public"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outputType").value("AUDIO"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.artifactExpired").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.apiKeyId").doesNotExist());
    }
}
