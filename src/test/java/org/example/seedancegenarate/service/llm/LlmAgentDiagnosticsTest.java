package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLHandshakeException;
import java.net.ProtocolException;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAgentDiagnosticsTest {
    private final HttpClient http = mock(HttpClient.class);
    private final LlmChatClient client = new LlmChatClient(new ObjectMapper(), http);
    private final LlmCallMeta agent = new LlmCallMeta("AGENT_PLAN", null, 3L, "turn", 4);
    private final LlmChannelSpec channel = new LlmChannelSpec("own", "http://unit.invalid/chat", "secret-key",
            "model", null, 8192, LlmChannelSpec.TokenParam.MAX_TOKENS, 300000, 1, true, false, "");

    // 【测什么】独立超时配置有有限边界，不改变同步通道的119秒配置约束。
    // 【怎么算红】删setTimeoutMs边界或改默认值则断言失败。
    @Test void backgroundConfigHasFiniteBounds() {
        var config = new AgentModelCallConfig();
        assertEquals(300000, config.getTimeoutMs());
        assertThrows(IllegalArgumentException.class, () -> config.setTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> config.setTimeoutMs(600001));
        config.setTimeoutMs(1000);
        config.setTimeoutMs(600000);
        assertEquals(119000, LlmChannelSpec.MAX_TIMEOUT_MS);
    }

    // 【测什么】瞬时错误才自动重试，认证/协议/上下文/配额失败不原样重试。
    // 【怎么算红】统一所有HTTP错误为retryable或丢失context/quota码会失败。
    @Test void httpClassificationIsSpecificAndConservative() {
        for (int status : new int[]{408,429,500,502,503,504}) {
            var error = client.httpFailure(status, "{}", "HTTP " + status);
            assertTrue(error.retryable(), "status=" + status);
            assertEquals(status, error.httpStatus());
            assertTrue(error.failoverable(), "同步路由策略不变");
        }
        for (int status : new int[]{400,401,403,404,413,501,505}) {
            assertFalse(client.httpFailure(status,"{}","HTTP " + status).retryable());
        }
        var context = client.httpFailure(400,"{\"error\":{\"code\":\"context_length_exceeded\"}}", "HTTP 400");
        assertEquals("MODEL_CONTEXT_OVERFLOW", context.code());
        assertFalse(context.retryable());
        var quota = client.httpFailure(429,"{\"error\":{\"code\":\"insufficient_quota\"}}", "HTTP 429");
        assertEquals("MODEL_QUOTA_EXHAUSTED", quota.code());
        assertFalse(quota.retryable());
        assertNull(client.httpFailure(500,"{\"error\":{\"code\":\"secret-key\"}}", "HTTP 500").providerCode());
    }

    // 【测什么】类型化读超时可供Agent重试，但同步路由仍不切换；证书和协议错误不可重试。
    // 【怎么算红】将retryable和failoverable复用一个布尔或SSL当瞬时IO则失败。
    @Test void typedTransportClassificationKeepsSynchronousContract() {
        var timeout = LlmChatClient.classify(new HttpTimeoutException("secret-key"));
        assertEquals("MODEL_TIMEOUT", timeout.code());
        assertTrue(timeout.retryable());
        assertFalse(timeout.failoverable());
        assertFalse(timeout.reason().contains("secret-key"));
        assertFalse(LlmChatClient.classify(new SSLHandshakeException("bad certificate")).retryable());
        assertFalse(LlmChatClient.classify(new ProtocolException("bad wire")).retryable());
        assertTrue(LlmChatClient.classify(new HttpConnectTimeoutException("timeout")).retryable());
    }

    // 【测什么】空正文长度耗尽保留真实usage，不把reasoning当业务结果；缺失usage仍未知。
    // 【怎么算红】把所有空响应归MODEL_PROVIDER_ERROR/丢失usage/取reasoning将失败。
    @Test void emptyLengthResponseRetainsUsageWithoutPromotingReasoning() throws Exception {
        reply("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null,\"reasoning_content\":\"private reasoning\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8192}}");
        var error = assertThrows(LlmChannelException.class, () -> call(agent));
        assertEquals("MODEL_OUTPUT_TRUNCATED", error.code());
        assertEquals(12, error.promptTokens());
        assertEquals(8192, error.completionTokens());
        assertFalse(error.retryable());
        assertFalse(error.reason().contains("private reasoning"));
        reply("{\"choices\":[{\"message\":{\"content\":[]}}],\"usage\":{\"prompt_tokens\":null,\"completion_tokens\":-1}}");
        var missing = assertThrows(LlmChannelException.class, () -> call(agent));
        assertEquals("MODEL_OUTPUT_INVALID", missing.code());
        assertNull(missing.promptTokens());
        assertNull(missing.completionTokens());
        reply("{\"choices\":[{\"message\":{\"content\":123}}]}");
        assertEquals("MODEL_OUTPUT_INVALID", assertThrows(LlmChannelException.class, () -> call(agent)).code());
    }

    // 【测什么】Agent截断正文不能冒充有效JSON，同步调用保留原来的正常文本返回，HTTP收到独立超时。
    // 【怎么算红】把length当成功或把同步试跑也强制判错、未传独立timeout即失败。
    @Test void truncatedBodyRejectedOnlyForAgentAndTimeoutReachesHttp() throws Exception {
        reply("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}");
        assertEquals("MODEL_OUTPUT_TRUNCATED", assertThrows(LlmChannelException.class, () -> call(agent)).code());
        assertEquals("partial", call(new LlmCallMeta("PROMPT_OPTIMIZE",null)).content());
        verify(http, times(2)).send(argThat(request -> request.timeout().orElseThrow().equals(Duration.ofMillis(300000))), any());
    }

    // 【测什么】Agent失败诊断不能将上游回显的正文/密钥泄漏到日志或短因。
    // 【怎么算红】Agent分支重新拼接providerDetail或打印响应正文则泄漏断言失败。
    @Test void agentFailureLogsOnlySafeMetadata() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LlmChatClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(503);
            when(response.body()).thenReturn("{\"error\":{\"code\":\"secret-key\",\"message\":\"private input http://private.secret\"}}");
            when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var error = assertThrows(LlmChannelException.class, () -> call(agent));
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.contains("code=MODEL_TEMPORARILY_UNAVAILABLE"));
            assertTrue(logs.contains("turn=turn, step=4"));
            assertFalse(logs.contains("secret-key"));
            assertFalse(logs.contains("private input"));
            assertFalse(logs.contains("private.secret"));
            assertEquals("HTTP 503", error.reason());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private LlmChatResponse call(LlmCallMeta meta) {
        return client.chat(channel,List.of(Map.of("role","user","content","private input")),meta);
    }
    private void reply(String body) throws Exception {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
