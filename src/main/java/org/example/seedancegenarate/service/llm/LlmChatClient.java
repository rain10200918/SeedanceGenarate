package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Chat Completions 调用<b>唯一出口</b>：所有 LLM 调用（提示词优化，及未来的翻译/打标等）都走这里。
 * TokenUsageAspect 切此类的 chat() 统一记录 token 消耗——按通道记，失败的那次也记。
 * <p>
 * 调哪个服务由传进来的 {@link LlmChannelSpec} 决定；这里不知道也不关心表和 yaml。
 * 密钥只在这里进 Authorization 头；日志和异常里只出现通道名和短因，不出现 URL 和 key（D-023）。
 *
 * <h3>为什么用 JDK HttpClient 而不是 Hutool</h3>
 * 路由要分「连接超时」（主机黑洞，可切下一条）和「读超时」（模型出字慢，不可切）。
 * JDK 把两者分成 {@link HttpConnectTimeoutException} 和 {@link HttpTimeoutException} 两个<b>类型</b>，
 * Hutool 都包成 IORuntimeException 只能看 message 文本——那是 D-028 明令禁止的判定方式。
 */
@Slf4j
@Component
public class LlmChatClient {

    private final ObjectMapper objectMapper;
    private final HttpClient http;

    // 两个构造器时 Spring 不会自己挑，缺了这个注解就去找无参构造器，启动直接失败
    @Autowired
    public LlmChatClient(PromptOptimizeConfig config, ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs() == null ? 5000 : config.getConnectTimeoutMs()))
                .build());
    }

    /** 测试用：注一个自定义 HttpClient（回环地址、极短超时） */
    LlmChatClient(ObjectMapper objectMapper, HttpClient http) {
        this.objectMapper = objectMapper;
        this.http = http;
    }

    /**
     * 对<b>一条</b>通道发一次 chat completions。
     *
     * @throws LlmChannelException 失败；{@link LlmChannelException#failoverable()} 告诉路由能不能切下一条
     */
    public LlmChatResponse chat(LlmChannelSpec channel, List<Map<String, Object>> messages, LlmCallMeta meta) {
        String scene = meta == null ? null : meta.scene();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(channel.baseUrl()))
                    .timeout(Duration.ofMillis(channel.timeoutMs()))
                    .header("Authorization", "Bearer " + channel.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody(channel, messages))))
                    .build();
        } catch (Exception e) {
            throw fail(channel, scene, LlmChannelException.failoverable("请求构造失败: " + e.getClass().getSimpleName(), e));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Throwable t) {
            throw fail(channel, scene, classify(t));
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 只记状态码，响应体可能回显请求（含 messages），不进日志
            throw fail(channel, scene, LlmChannelException.failoverable(httpReason(status), null));
        }

        String content;
        Integer promptTokens;
        Integer completionTokens;
        try {
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode contentNode = node.path("choices").path(0).path("message").path("content");
            content = contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText("").trim();
            JsonNode usage = node.path("usage");
            promptTokens = usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt();
            completionTokens = usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt();
        } catch (Exception e) {
            throw fail(channel, scene, LlmChannelException.failoverable("响应解析失败", e));
        }
        if (content.isEmpty()) {
            // 推理类模型会把正文放进别的字段而让 content 为空；空字符串交出去等于让用户拿到一条空提示词
            throw fail(channel, scene, LlmChannelException.failoverable("空响应（content 为空）", null));
        }
        return new LlmChatResponse(content, promptTokens, completionTokens);
    }

    /**
     * 请求体。<b>形状固定</b>，只允许两处按通道加减：temperature 传不传、max token 那个字段叫什么。
     * 再多一个开关就是在造模板引擎，那时候该老实写一个新的 engine 类。
     */
    static Map<String, Object> requestBody(LlmChannelSpec channel, List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", channel.model());
        body.put("messages", messages);
        if (channel.temperature() != null) {
            body.put("temperature", channel.temperature());
        }
        String tokenField = channel.tokenParam() == null ? "max_tokens" : channel.tokenParam().field();
        if (tokenField != null) {
            body.put(tokenField, channel.maxTokens());
        }
        body.put("stream", false);
        return body;
    }

    /**
     * 按<b>类型</b>把传输层异常分成能切 / 不能切。连接超时和读超时是两个不同的类，不看 message。
     */
    static LlmChannelException classify(Throwable t) {
        if (t instanceof HttpConnectTimeoutException) {
            // 主机黑洞：SYN 不回。连接层就能定，切下一条还有整段预算
            return LlmChannelException.failoverable("connect timeout", t);
        }
        if (t instanceof HttpTimeoutException) {
            // 已经等了整个读超时，预算花完了
            return LlmChannelException.readTimeout(t);
        }
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LlmChannelException.terminal("interrupted", t);
        }
        if (t instanceof IOException) {
            // ConnectException（被拒）、SSL、连接被重置……都是快失败
            return LlmChannelException.failoverable("io: " + t.getClass().getSimpleName(), t);
        }
        return LlmChannelException.failoverable("unexpected: " + t.getClass().getSimpleName(), t);
    }

    /** 状态码的短因。401/403 单独点出「配置错误」——它不是瞬时故障，切走之后必须有人去修 */
    static String httpReason(int status) {
        if (status == 401 || status == 403) {
            return "HTTP " + status + " 认证失败（密钥或权限，属配置错误）";
        }
        if (status == 429) {
            return "HTTP 429 配额或限速";
        }
        if (status == 400) {
            return "HTTP 400（参数不兼容或内容策略拒绝）";
        }
        return "HTTP " + status;
    }

    private LlmChannelException fail(LlmChannelSpec channel, String scene, LlmChannelException e) {
        if (e.failoverable()) {
            log.warn("LLM 通道 {} 调用失败[{}], scene={}, model={}", channel.name(), e.reason(), scene, channel.model());
        } else {
            log.warn("LLM 通道 {} 调用失败[{}]，不切换, scene={}, model={}", channel.name(), e.reason(), scene, channel.model());
        }
        return e;
    }
}
