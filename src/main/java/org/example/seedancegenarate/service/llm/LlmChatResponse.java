package org.example.seedancegenarate.service.llm;

/**
 * LLM Chat Completions 响应：内容 + usage（token 数）。
 * usage 缺失（部分代理不返回）时为 null，由统计侧按字符数估算兜底。
 */
public record LlmChatResponse(
        /** 模型返回的正文（已 trim） */
        String content,
        /** 输入 token 数（usage.prompt_tokens），缺失为 null */
        Integer promptTokens,
        /** 输出 token 数（usage.completion_tokens），缺失为 null */
        Integer completionTokens
) {
}
