package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.PromptTemplateService;
import org.example.seedancegenarate.service.llm.LlmCallMeta;
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.example.seedancegenarate.service.llm.LlmRouter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptOptimizeServiceImpl implements PromptOptimizeService {

    /** 通用输出铁律，追加在任何模板末尾，保证输出可直接使用。输出语言由各模板自行规定（如 h3 模板要求英文正文、对白保留原文）。 */
    private static final String OUTPUT_FOOTER =
            "只输出最终优化后的提示词本身，不要输出分析过程、标题、说明、引号或任何额外内容；输出语言、结构与格式一律以模板要求为准。";
    private final PromptTemplateService templates = new PromptTemplateService();

    /** 用哪条 LLM 通道由路由决定；这里只关心模板和消息。「未配置」也由路由报 */
    private final LlmRouter llmRouter;

    @Override
    public String optimize(String prompt, PromptContext context) throws Exception {
        String targetModel = context == null ? null : context.model();
        LlmChatResponse response = llmRouter.chat(buildMessages(prompt, context),
                new LlmCallMeta(LlmCallMeta.SCENE_PROMPT_OPTIMIZE, targetModel));
        return finish(response);
    }

    @Override
    public LlmChatResponse optimizeWith(String channelName, String prompt, PromptContext context) {
        String targetModel = context == null ? null : context.model();
        LlmChatResponse response = llmRouter.chatWith(channelName, buildMessages(prompt, context),
                new LlmCallMeta(LlmCallMeta.SCENE_PROMPT_OPTIMIZE_TRIAL, targetModel));
        return new LlmChatResponse(finish(response), response.promptTokens(), response.completionTokens());
    }

    private List<Map<String, Object>> buildMessages(String prompt, PromptContext context) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", buildSystemPrompt(context)));
        messages.add(message("user", prompt));
        return messages;
    }

    /** 去掉模型爱加的包裹引号；剥完还是空的就不能交给用户 */
    private String finish(LlmChatResponse response) {
        String optimized = stripWrappingQuotes(response.content());
        if (optimized.isEmpty()) {
            throw new RuntimeException("提示词优化失败，请稍后再试");
        }
        return optimized;
    }

    /** 系统提示 = 按 model 选中的模板（注入上下文）+ 通用输出铁律 */
    String buildSystemPrompt(PromptContext context) {
        return templates.guide(context) + "\n\n" + OUTPUT_FOOTER;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String stripWrappingQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '“' && last == '”')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }
}
