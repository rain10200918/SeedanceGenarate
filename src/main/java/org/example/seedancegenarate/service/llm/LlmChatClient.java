package org.example.seedancegenarate.service.llm;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Chat Completions 调用唯一出口：所有 LLM 调用（提示词优化，及未来的
 * 翻译/打标等）都走这里。TokenUsageAspect 切此类的 chat() 统一记录 token 消耗。
 * <p>
 * 密钥（Authorization）仅在此处使用，绝不下发前端；调用失败仅记录状态码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatClient {

    private final PromptOptimizeConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 调用 OpenAI 兼容 Chat Completions 接口。
     *
     * @param model    模型 ID
     * @param messages 消息列表（role/content map）
     * @param meta     业务上下文（scene + targetModel），切面统计用
     * @return 内容 + usage；usage 缺失时为 null
     */
    public LlmChatResponse chat(String model, List<Map<String, Object>> messages, LlmCallMeta meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", false);

        HttpResponse response;
        try {
            String json = objectMapper.writeValueAsString(body);
            response = HttpRequest.post(config.getUrl())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(config.getTimeoutMs())
                    .body(json)
                    .execute();
        } catch (Exception e) {
            // 序列化/连接异常：记录调用场景与错误类别，不写请求体与密钥
            log.warn("LLM 调用异常, scene:{}, model:{}, err:{}", meta == null ? null : meta.scene(), model, e.getMessage());
            throw new RuntimeException("提示词优化服务调用失败，请稍后再试", e);
        }

        if (!response.isOk()) {
            // 仅记录状态码，避免把密钥或完整请求写进日志
            log.error("LLM 调用失败，状态码:{}", response.getStatus());
            throw new RuntimeException("提示词优化服务调用失败，请稍后再试");
        }

        try {
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode contentNode = node.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() ? "" : contentNode.asText("").trim();
            JsonNode usage = node.path("usage");
            Integer promptTokens = usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt();
            Integer completionTokens = usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt();
            return new LlmChatResponse(content, promptTokens, completionTokens);
        } catch (Exception e) {
            log.warn("LLM 响应解析失败, scene:{}, err:{}", meta == null ? null : meta.scene(), e.getMessage());
            throw new RuntimeException("提示词优化服务调用失败，请稍后再试", e);
        }
    }
}
