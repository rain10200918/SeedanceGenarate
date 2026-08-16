package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.llm.LlmCallMeta;
import org.example.seedancegenarate.service.llm.LlmChatClient;
import org.example.seedancegenarate.service.llm.LlmChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    /** 模板文件都缺失时的兜底指导 */
    private static final String FALLBACK_GUIDE =
            "你是 AI 生成提示词专家，请把用户的粗略描述改写成一条高质量、结构清晰的提示词。";

    private final PromptOptimizeConfig config;
    private final LlmChatClient llmChatClient;

    @Override
    public String optimize(String prompt, PromptContext context) throws Exception {
        if (config.getApiKey() == null || config.getApiKey().isBlank()
                || config.getUrl() == null || config.getUrl().isBlank()
                || config.getModel() == null || config.getModel().isBlank()) {
            throw new RuntimeException("提示词优化服务未配置，请联系管理员");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", buildSystemPrompt(context)));
        messages.add(message("user", prompt));

        // LLM 调用统一走 LlmChatClient（TokenUsageAspect 在此切面记录 token 消耗）
        String targetModel = context == null ? null : context.model();
        LlmChatResponse response = llmChatClient.chat(config.getModel(), messages,
                new LlmCallMeta(LlmCallMeta.SCENE_PROMPT_OPTIMIZE, targetModel));

        String optimized = stripWrappingQuotes(response.content());
        if (optimized.isEmpty()) {
            throw new RuntimeException("提示词优化失败，请稍后再试");
        }
        return optimized;
    }

    /** 系统提示 = 按 model 选中的模板（注入上下文）+ 通用输出铁律 */
    String buildSystemPrompt(PromptContext context) {
        String model = context == null ? null : context.model();
        String guide = injectContext(loadGuide(model), context);
        return guide + "\n\n" + OUTPUT_FOOTER;
    }

    /** 载入 prompts/{model}.md；缺失回退 prompts/default.md；再缺失用兜底常量 */
    private String loadGuide(String model) {
        String guide = null;
        if (model != null && !model.isBlank()) {
            guide = readClasspath("prompts/" + model + ".md");
        }
        if (guide == null) {
            guide = readClasspath("prompts/default.md");
        }
        return guide == null ? FALLBACK_GUIDE : guide;
    }

    private String readClasspath(String path) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
            return null;
        }
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("读取提示词模板失败: {}", path);
            return null;
        }
    }

    /** 把上下文注入模板占位符；未提供的置为空/0 */
    private String injectContext(String template, PromptContext ctx) {
        if (ctx == null) {
            return template;
        }
        return template
                .replace("{imageCount}", String.valueOf(ctx.imageCount() == null ? 0 : ctx.imageCount()))
                .replace("{videoCount}", String.valueOf(ctx.videoCount() == null ? 0 : ctx.videoCount()))
                .replace("{audioCount}", String.valueOf(ctx.audioCount() == null ? 0 : ctx.audioCount()))
                .replace("{duration}", ctx.duration() == null ? "" : String.valueOf(ctx.duration()))
                .replace("{ratio}", ctx.ratio() == null ? "" : ctx.ratio())
                .replace("{model}", ctx.model() == null ? "" : ctx.model());
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
