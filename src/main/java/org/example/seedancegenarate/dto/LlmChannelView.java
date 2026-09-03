package org.example.seedancegenarate.dto;

import org.example.seedancegenarate.service.llm.LlmChannelSpec;

/**
 * 管理端看到的一条 LLM 通道。<b>密钥只有脱敏形态</b>——这个类型上根本没有明文字段，
 * 想返回也返回不了（结构守卫，比"记得别返回"可靠）。
 */
public record LlmChannelView(
        String name,
        String baseUrl,
        /** 前 6 后 2，中间遮住 */
        String apiKeyMasked,
        String model,
        Double temperature,
        int maxTokens,
        /** max_tokens / max_completion_tokens / none */
        String tokenParam,
        int timeoutMs,
        int priority,
        boolean enabled,
        boolean archived,
        String remark
) {
    public static LlmChannelView of(LlmChannelSpec s) {
        return new LlmChannelView(s.name(), s.baseUrl(), LlmChannelSpec.maskKey(s.apiKey()), s.model(),
                s.temperature(), s.maxTokens(), s.tokenParam().stored(), s.timeoutMs(), s.priority(),
                s.enabled(), s.archived(), s.remark());
    }
}
