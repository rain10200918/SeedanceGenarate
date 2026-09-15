package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.*;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiVideoP0Test {
    private final ApiCallLogMapper logs = mock(ApiCallLogMapper.class);
    private final VideoSubmitService submit = mock(VideoSubmitService.class);
    private final VideoEngine engine = mock(VideoEngine.class);
    private final ApiReferenceStorage storage = mock(ApiReferenceStorage.class);
    private final VideoTaskService tasks = mock(VideoTaskService.class);
    private final ApiKey key = new ApiKey();
    private ApiVideoServiceImpl api;
    private static final String URL = "https://8.8.8.8/reference.png";

    @BeforeEach void setup() throws Exception {
        key.setId(1L); key.setUserId(2L);
        when(engine.provider()).thenReturn("test");
        when(engine.models()).thenReturn(List.of(new ModelSpec("test", "model", "Model", false,
                0, 16, List.of("16:9"), 5, 10, List.of(5, 8, 10), OutputType.VIDEO,
                List.of(1.0), 2, 2, false)));
        api = spy(new ApiVideoServiceImpl(logs, submit, new VideoEngineRegistry(List.of(engine)), storage, tasks));
        // 即使守卫被变异移除，测试也绝不访问真实外网；需要下载的用例显式提供假连接。
        doThrow(new AssertionError("unexpected real network access")).when(api).openReferenceConnection(any());
    }

    private ApiVideoService.CreateContext context(List<String> images, Integer duration, String ratio, Double mp) {
        return new ApiVideoService.CreateContext(key, "p0", "1.1.1.1", "test", "prompt", "model",
                images, List.of(), List.of(), duration, ratio, mp);
    }

    // 【测什么】公开API档位解析在下载/落库前，成功请求把解析MP传给共享提交且原resolution保留复验。
    // 【怎么算红】漏resolution校验或落回context.megapixels会使400/零副作用/0.9断言变红。
    @Test void resolutionValidationAndSubmissionUseDeclaredMapping() throws Exception {
        var spec=new ModelSpec("test","model","HD",false,0,16,List.of("16:9"),5,10,List.of(5,8,10),
                OutputType.VIDEO,List.of(0.2,0.5,0.9),2,2,false)
                .withResolutions(List.of(new ModelSpec.ResolutionOption("2k",0.9,true)),0.2);
        when(engine.models()).thenReturn(List.of(spec));
        for (String tier:List.of("4k"," ","2K")) {
            var input=new ApiVideoService.CreateContext(key,"resolution-bad","ip","ua","p","model",
                    List.of(URL),List.of(),List.of(),8,"16:9",null,tier);
            assertEquals(400,assertThrows(ApiException.class,()->api.create(input)).getHttpStatus().value());
        }
        var conflict=new ApiVideoService.CreateContext(key,"resolution-conflict","ip","ua","p","model",
                List.of(URL),List.of(),List.of(),8,"16:9",0.5,"2k");
        assertEquals(400,assertThrows(ApiException.class,()->api.create(conflict)).getHttpStatus().value());
        verify(api,never()).openReferenceConnection(any());verifyNoInteractions(storage);
        verify(logs,never()).insert(any(ApiCallLog.class));verify(submit,never()).submit(any());
        var task=new VideoTask();task.setId(22L);task.setBizTaskId("tier-task");task.setStatus("PROCESSING");
        when(submit.submit(any())).thenReturn(task);
        when(logs.linkTaskByRequestId(any(),any(),anyString(),anyString(),any())).thenReturn(1);
        api.create(new ApiVideoService.CreateContext(key,"resolution-ok","ip","ua","p","model",
                List.of(),List.of(),List.of(),8,"16:9",null,"2k"));
        var captured=org.mockito.ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submit).submit(captured.capture());assertEquals(0.9,captured.getValue().megapixels());
        assertEquals("2k",captured.getValue().resolution());
        var logged=org.mockito.ArgumentCaptor.forClass(ApiCallLog.class);verify(logs).insert(logged.capture());
        assertEquals(0.9,logged.getValue().getMegapixels());
    }

    private HttpURLConnection connection(long length, InputStream stream) throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentLengthLong()).thenReturn(length);
        when(connection.getContentType()).thenReturn("image/png");
        when(connection.getInputStream()).thenReturn(stream);
        return connection;
    }

    private ApiReferenceStorage.OwnedReference uploaded() {
        var handle = mock(ApiReferenceStorage.OwnedReference.class);
        when(handle.url()).thenReturn("https://oss.test/api-references/owned.png");
        when(storage.upload(any(), eq(".png"))).thenReturn(handle);
        return handle;
    }

    // 【测什么】非法时长、比例、分辨率、素材数量均在网络/落库/提交之前返回400。
    // 【怎么算红】删除API纯校验调用后，submit被调用或错误码不是VALIDATION_ERROR。
    @Test void invalidParametersHaveNoSideEffects() throws Exception {
        for (var context : List.of(context(List.of(URL), 6, "16:9", null),
                context(List.of(URL), 5, "4:5", null), context(List.of(URL), 5, "16:9", Double.NaN),
                context(java.util.Collections.nCopies(17, URL), 5, "16:9", null))) {
            var error = assertThrows(ApiException.class, () -> api.create(context));
            assertEquals("VALIDATION_ERROR", error.getCode());
            assertEquals(400, error.getHttpStatus().value());
        }
        verify(api, never()).openReferenceConnection(any());
        verifyNoInteractions(storage);
        verify(logs, never()).insert(any(ApiCallLog.class));
        verify(submit, never()).submit(any());
    }

    // 【测什么】全部URL先预检，最后一个私网/空白/超长不能让前几个先下载。
    // 【怎么算红】移除全组precheckReferences后第一个URL会打开连接。
    @Test void allUrlsCheckedBeforeFirstDownload() throws Exception {
        for (String invalid : List.of("http://127.0.0.1/secret", "http://[fd00::1]/secret",
                "http://[fc00::1]/secret", "https://8.8.8.8:99999/a.png", "https://8.8.8.8:0/a.png",
                "https://user:password@8.8.8.8/a.png", " ", "https://8.8.8.8/" + "a".repeat(4096))) {
            assertThrows(ApiException.class, () -> api.create(context(List.of(URL, invalid), 5, "16:9", null)));
        }
        verify(api, never()).openReferenceConnection(any());
        verifyNoInteractions(storage);
    }

    // 【测什么】第二个下载失败会清理已经上传的第一个，仅删除自己持有的handle。
    // 【怎么算红】删除finally清理或传错handle，精确delete断言失败。
    @Test void secondDownloadFailureCleansFirstOwnedObject() throws Exception {
        var first = connection(1, new ByteArrayInputStream(new byte[]{1}));
        var second = mock(HttpURLConnection.class);
        when(second.getResponseCode()).thenReturn(503);
        doReturn(first, second).when(api).openReferenceConnection(any());
        var owned = uploaded();
        assertThrows(ApiException.class, () -> api.create(context(List.of(URL, URL), 5, "16:9", null)));
        verify(storage).delete(owned);
        verify(submit, never()).submit(any());
        verify(first).disconnect(); verify(second).disconnect();
    }

    // 【测什么】提交明确失败且任务不存在时清理；DB查不清或任务仍在时保留。
    // 【怎么算红】只凭异常删素材会令后两轮never(delete)失败，不清理会令首轮失败。
    @Test void cleanupRequiresProofOfNonAcceptance() throws Exception {
        for (int state = 0; state < 3; state++) {
            reset(storage, submit);
            var owned = uploaded();
            doReturn(connection(1, new ByteArrayInputStream(new byte[]{1}))).when(api).openReferenceConnection(any());
            when(submit.submit(any())).thenThrow(new IllegalStateException("submission failed"));
            if (state == 1) when(submit.findByRequestId(2L, "api:p0")).thenReturn(new VideoTask());
            if (state == 2) when(submit.findByRequestId(2L, "api:p0")).thenThrow(new IllegalStateException("db unavailable"));
            assertThrows(ApiException.class, () -> api.create(context(List.of(URL), 5, "16:9", null)));
            verify(storage, state == 0 ? times(1) : never()).delete(owned);
        }
    }

    // 【测什么】任务已返回但调用日志补链失败，必须保留参考素材。
    // 【怎么算红】受理后无条件finally清理会触发delete。
    @Test void acceptedTaskKeepsReferencesWhenLogLinkFails() throws Exception {
        var owned = uploaded();
        doReturn(connection(1, new ByteArrayInputStream(new byte[]{1}))).when(api).openReferenceConnection(any());
        var task = new VideoTask(); task.setBizTaskId("tsk_accepted");
        when(submit.submit(any())).thenReturn(task);
        assertThrows(ApiException.class, () -> api.create(context(List.of(URL), null, null, null)));
        verify(storage, never()).delete(owned);
        verify(submit).submit(argThat(r -> r.duration() == 8 && "16:9".equals(r.ratio())));
    }

    // 【测什么】同幂等键日志争抢输家只清理自己的对象，不提交第二个任务。
    // 【怎么算红】去掉DuplicateKey处理清理或继续submit使断言失败。
    @Test void duplicateLogLoserCleansOwnUpload() throws Exception {
        var owned = uploaded();
        doReturn(connection(1, new ByteArrayInputStream(new byte[]{1}))).when(api).openReferenceConnection(any());
        when(logs.insert(any(ApiCallLog.class))).thenThrow(new DuplicateKeyException("race"));
        var error = assertThrows(ApiException.class, () -> api.create(context(List.of(URL), 5, "16:9", null)));
        assertEquals("REQUEST_IN_PROGRESS", error.getCode());
        verify(storage, times(1)).delete(owned);
        verify(submit, never()).submit(any());
    }

    private InputStream bytes(long length) {
        return new InputStream() {
            long remaining = length;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] buffer, int offset, int len) {
                if (remaining <= 0) return -1;
                int count = (int) Math.min(remaining, len);
                remaining -= count;
                return count;
            }
        };
    }

    // 【测什么】无Content-Length也受30MiB单件限制，读取超一字节即失败且不上传。
    // 【怎么算红】删除流读取中的单件限制会触发storage.upload。
    @Test void unknownLengthStreamEnforcesPerFileLimit() throws Exception {
        doReturn(connection(-1, bytes(30L * 1024 * 1024 + 1))).when(api).openReferenceConnection(any());
        var error = assertThrows(ApiException.class, () -> api.create(context(List.of(URL), 5, "16:9", null)));
        assertTrue(error.getMessage().contains("30MB"));
        verifyNoInteractions(storage);
    }

    // 【测什么】四个25MiB正好允许累计100MiB，第五个字节超限且清理前四个对象。
    // 【怎么算红】删除累计流限制后会发生第五次upload，或删错对象使delete次数失败。
    @Test void aggregateStreamLimitCleansEarlierUploads() throws Exception {
        var owned = uploaded();
        var remainingConnections = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> connection(-1, bytes(remainingConnections.getAndIncrement() < 4
                ? 25L * 1024 * 1024 : 1))).when(api).openReferenceConnection(any());
        var error = assertThrows(ApiException.class, () -> api.create(
                context(java.util.Collections.nCopies(5, URL), 5, "16:9", null)));
        assertTrue(error.getMessage().contains("100MiB"));
        verify(storage, times(4)).upload(any(), eq(".png"));
        verify(storage, times(4)).delete(owned);
        verify(submit, never()).submit(any());
    }

    // 【测什么】真实MVC JSON绑定→API编排→纯校验→advice返回400，超大体413，合法请求202。
    // 【怎么算红】移除参数校验/请求体过滤/advice状态码后，对应HTTP断言失败。
    @Test void httpContractSmoke() throws Exception {
        var task = new VideoTask(); task.setBizTaskId("tsk_smoke"); task.setStatus("PROCESSING");
        when(submit.submit(any())).thenReturn(task);
        when(logs.linkTaskByRequestId(any(), any(), anyString(), anyString(), any())).thenReturn(1);
        var controller = new org.example.seedancegenarate.controller.ApiVideoController(
                api, tasks, null, null, null, null, null);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new org.example.seedancegenarate.exception.ApiExceptionHandler())
                .addFilters(new org.example.seedancegenarate.config.ApiGenerationRequestSizeFilter(
                        new com.fasterxml.jackson.databind.ObjectMapper())).build();
        var invalid = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/videos").requestAttr("api_key", key).header("Idempotency-Key", "p0")
                        .contentType("application/json").content("{\"prompt\":\"test\",\"model\":\"model\",\"duration\":6}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();
        System.out.println("P0 HTTP 400: " + invalid.getResponse().getContentAsString());
        var oversized = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/videos").contentType("application/json").content(new byte[65537]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge())
                .andReturn();
        System.out.println("P0 HTTP 413: " + oversized.getResponse().getContentAsString());
        var accepted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/videos").requestAttr("api_key", key).header("Idempotency-Key", "p0")
                        .contentType("application/json").content("{\"prompt\":\"test\",\"model\":\"model\",\"duration\":5}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.taskId").value("tsk_smoke"))
                .andReturn();
        System.out.println("P0 HTTP 202: " + accepted.getResponse().getContentAsString());
        verifyNoInteractions(storage);
    }
}
