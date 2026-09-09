package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import javax.net.ssl.SSLException;
import java.net.ProtocolException;
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
 * 文本 Chat Completions 出口：同步优化、文本 Skill 与 Recipe 编译沿用本客户端；
 * Agent Planner 结构化协议另走 {@link LangChain4jPlannerClient}。
 * TokenUsageAspect 覆盖两个客户端的 chat()，按通道记录 token 消耗，失败调用也记。
 * <p>
 * 调哪个服务由传进来的 {@link LlmChannelSpec} 决定；这里不知道也不关心表和 yaml。
 * 密钥仅用于模型请求的 Authorization 头；日志和异常不出现 URL 和 key（D-023）。
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
                // 默认 HTTP_2 会对 http:// 地址先发 "Upgrade: h2c"；uvicorn（vLLM / SGLang）对非 WebSocket 的
                // Upgrade 直接回 400 "Unsupported upgrade request."——线上两条通道全 400 就是这个
                .version(HttpClient.Version.HTTP_1_1)
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
        long started = System.nanoTime();
        int inputChars = inputTextChars(messages);
        log.info("LLM request turn={}, step={}, scene={}, channel={}, model={}, timeoutMs={}, outputLimit={}, tokenParam={}, inputChars={}",
                meta == null ? null : meta.agentTurnId(), meta == null ? null : meta.decisionStep(),
                scene, channel.name(), channel.model(), channel.timeoutMs(), channel.maxTokens(), channel.tokenParam(), inputChars);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(channel.baseUrl()))
                    .timeout(Duration.ofMillis(channel.timeoutMs()))
                    .header("Authorization", "Bearer " + channel.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody(channel, messages))))
                    .build();
        } catch (Exception e) {
            throw fail(channel, meta, LlmChannelException.failoverable("请求构造失败: " + e.getClass().getSimpleName(), e)
                    .classified("MODEL_INVALID_REQUEST", false, null, null, null, null));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Throwable t) {
            throw fail(channel, meta, classify(t));
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 带上服务端那句错误原因（≤200 字），否则线上只看到「400」谁也修不了；不带 URL 和 key
            String detail = meta != null && meta.agentTurnId() != null ? "" : providerDetail(response.body(), channel.apiKey());
            throw fail(channel, meta, httpFailure(status, response.body(), httpReason(status) + detail));
        }

        String content;
        Integer promptTokens;
        Integer completionTokens;
        String finishReason;
        try {
            JsonNode node = objectMapper.readTree(response.body());
            if (node == null) throw new IllegalArgumentException("empty JSON document");
            JsonNode contentNode = node.path("choices").path(0).path("message").path("content");
            content = contentNode.isMissingNode() || contentNode.isNull() ? "" : contentNode.asText("").trim();
            if (meta != null && meta.agentTurnId() != null && !contentNode.isTextual()) content = "";
            JsonNode usage = node.path("usage");
            promptTokens = usageCount(usage.path("prompt_tokens"));
            completionTokens = usageCount(usage.path("completion_tokens"));
            finishReason = knownFinishReason(node.path("choices").path(0).path("finish_reason").asText(""));
            JsonNode reasoning = node.path("choices").path(0).path("message").path("reasoning_content");
            log.info("LLM response turn={}, step={}, scene={}, channel={}, model={}, httpStatus={}, latencyMs={}, choices={}, contentType={}, contentChars={}, reasoningChars={}, finishReason={}, promptTokens={}, completionTokens={}",
                    meta == null ? null : meta.agentTurnId(), meta == null ? null : meta.decisionStep(), scene,
                    channel.name(), channel.model(), status, (System.nanoTime() - started) / 1000000,
                    node.path("choices").size(), contentNode.getNodeType(), content.length(),
                    reasoning.isTextual() ? reasoning.textValue().length() : null, finishReason, promptTokens, completionTokens);
        } catch (Exception e) {
            throw fail(channel, meta, LlmChannelException.failoverable("响应解析失败", e)
                    .classified("MODEL_OUTPUT_INVALID", false, status, null, null, null));
        }
        if (content.isEmpty()) {
            // 推理类模型会把正文放进别的字段而让 content 为空；空字符串交出去等于让用户拿到一条空提示词
            throw fail(channel, meta, LlmChannelException.failoverable("空响应（content 为空）", null)
                    .classified("length".equals(finishReason) ? "MODEL_OUTPUT_TRUNCATED" : "MODEL_OUTPUT_INVALID",
                            false, status, null, promptTokens, completionTokens));
        }
        if (meta != null && meta.agentTurnId() != null && "length".equals(finishReason)) {
            throw fail(channel, meta, LlmChannelException.failoverable("输出达到长度上限", null)
                    .classified("MODEL_OUTPUT_TRUNCATED", false, status, null, promptTokens, completionTokens));
        }
        return new LlmChatResponse(content, promptTokens, completionTokens);
    }

    /**
     * 请求体。<b>形状固定</b>，只允许两处按通道加减：temperature 传不传、max token 那个字段叫什么。
     * 再多一个开关就是在造模板引擎，那时候该老实写一个新的 engine 类。
     */
    /** Text-only size metric. Image token usage must come from the provider, not URL length. */
    public static int inputTextChars(Object messages) {
        if(!(messages instanceof List<?> list))return 0;
        int count=0;
        for(var raw:list) if(raw instanceof Map<?,?> message) {
            Object content=message.get("content");
            if(content instanceof String text)count+=text.length();
            else if(content instanceof List<?> parts)for(var p:parts)
                if(p instanceof Map<?,?> part && "text".equals(part.get("type")) && part.get("text") instanceof String text)
                    count+=text.length();
        }
        return count;
    }

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
            return LlmChannelException.failoverable("connect timeout", t)
                    .classified("MODEL_TIMEOUT", true, null, null, null, null);
        }
        if (t instanceof HttpTimeoutException) {
            // 已经等了整个读超时，预算花完了
            return LlmChannelException.readTimeout(t);
        }
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LlmChannelException.terminal("interrupted", t)
                    .classified("MODEL_CANCELLED", false, null, null, null, null);
        }
        if (t instanceof SSLException || t instanceof ProtocolException) {
            return LlmChannelException.failoverable("transport configuration: " + t.getClass().getSimpleName(), t)
                    .classified("MODEL_INVALID_REQUEST", false, null, null, null, null);
        }
        if (t instanceof IOException) {
            // Non-protocol IO (connection refused/reset etc.); certificate/protocol failures were excluded above.
            return LlmChannelException.failoverable("io: " + t.getClass().getSimpleName(), t)
                    .classified("MODEL_TEMPORARILY_UNAVAILABLE", true, null, null, null, null);
        }
        return LlmChannelException.failoverable("unexpected: " + t.getClass().getSimpleName(), t);
    }

    private static Integer usageCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
    }

    private static String knownFinishReason(String value) {
        return switch (value) {
            case "length", "stop", "tool_calls", "function_call", "content_filter" -> value;
            default -> "unknown";
        };
    }

    LlmChannelException httpFailure(int status, String body, String reason) {
        String providerCode = null;
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String raw = error.path("code").asText("");
            if (raw.isEmpty()) raw = error.path("type").asText("");
            // Only known code tokens may enter telemetry; unknown fields can echo a user's prompt or key.
            providerCode = switch (raw) {
                case "context_length_exceeded", "max_tokens_exceeded", "insufficient_quota", "quota_exceeded",
                        "billing_hard_limit_reached", "model_not_found", "rate_limit_exceeded",
                        "server_overloaded", "overloaded_error" -> raw;
                default -> null;
            };
        } catch (Exception ignored) { /* Non-JSON proxy response: status remains the evidence. */ }
        String code;
        if (status == 401 || status == 403) code = "MODEL_AUTHENTICATION_FAILED";
        else if ("context_length_exceeded".equals(providerCode) || "max_tokens_exceeded".equals(providerCode)) code = "MODEL_CONTEXT_OVERFLOW";
        else if ("insufficient_quota".equals(providerCode) || "quota_exceeded".equals(providerCode)
                || "billing_hard_limit_reached".equals(providerCode)) code = "MODEL_QUOTA_EXHAUSTED";
        else if (status == 404 || "model_not_found".equals(providerCode)) code = "MODEL_NOT_FOUND";
        else if (status == 429) code = "MODEL_RATE_LIMITED";
        else if (status == 408) code = "MODEL_TIMEOUT";
        else if (status == 500 || status == 502 || status == 503 || status == 504) code = "MODEL_TEMPORARILY_UNAVAILABLE";
        else if (status >= 400 && status < 500) code = "MODEL_INVALID_REQUEST";
        else code = "MODEL_PROVIDER_ERROR";
        boolean retryable = "MODEL_TIMEOUT".equals(code) || "MODEL_RATE_LIMITED".equals(code)
                || "MODEL_TEMPORARILY_UNAVAILABLE".equals(code);
        return LlmChannelException.failoverable(reason, null).classified(code, retryable, status, providerCode, null, null);
    }

    /**
     * 服务端错误正文里那句话：OpenAI 形状取 error.message，FastAPI 形状取 detail，否则原文；压成一行、截 200 字。
     * 这是运营在试跑结果里能看到的唯一线索（"maximum context length is 4096 tokens" / "Unsupported upgrade request."）。
     * 第三方 401 会把我们发过去的 key 回显在正文里，所以 key 和任何 URL 先抹掉再往外给（D-023）。
     */
    String providerDetail(String body, String apiKey) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body.strip();
        try {
            JsonNode node = objectMapper.readTree(text);
            JsonNode message = node.path("error").path("message");
            if (!message.isTextual()) {
                message = node.path("message");
            }
            if (!message.isTextual()) {
                message = node.path("detail");
            }
            if (message.isTextual() && !message.asText().isBlank()) {
                text = message.asText();
            }
        } catch (Exception ignored) {
            // 不是 JSON（uvicorn 的 "Unsupported upgrade request." 就是纯文本），原文即线索
        }
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey.trim(), "<key>");
        }
        text = text.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "<key>")
                .replaceAll("\\bsk-[A-Za-z0-9._\\-]{4,}", "<key>")
                .replaceAll("https?://\\S+", "<url>")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > 200) {
            text = text.substring(0, 200) + "…";
        }
        return text.isEmpty() ? "" : ": " + text;
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

    private LlmChannelException fail(LlmChannelSpec channel, LlmCallMeta meta, LlmChannelException e) {
        String scene = meta == null ? null : meta.scene();
        if (meta != null && meta.agentTurnId() != null) {
            log.warn("LLM failure turn={}, step={}, scene={}, channel={}, model={}, code={}, retryable={}, httpStatus={}, providerCode={}, exceptionClass={}, timeoutMs={}, outputLimit={}, promptTokens={}, completionTokens={}",
                    meta.agentTurnId(), meta.decisionStep(), scene, channel.name(), channel.model(), e.code(), e.retryable(),
                    e.httpStatus(), e.providerCode(), e.getCause() == null ? null : e.getCause().getClass().getSimpleName(),
                    channel.timeoutMs(), channel.maxTokens(), e.promptTokens(), e.completionTokens());
            return e;
        }
        if (e.failoverable()) {
            log.warn("LLM 通道 {} 调用失败[{}], scene={}, model={}", channel.name(), e.reason(), scene, channel.model());
        } else {
            log.warn("LLM 通道 {} 调用失败[{}]，不切换, scene={}, model={}", channel.name(), e.reason(), scene, channel.model());
        }
        return e;
    }
}
