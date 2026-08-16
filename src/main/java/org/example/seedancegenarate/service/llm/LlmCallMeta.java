package org.example.seedancegenarate.service.llm;

/**
 * LLM 调用业务上下文：供切面（TokenUsageAspect）记录消耗时区分用途与目标。
 * <p>
 * 复用规则：新增 LLM 调用场景时，调用 LlmChatClient.chat() 并传入对应的 scene，
 * 无需改切面即可自动纳入统计。
 */
public record LlmCallMeta(
        /** 用途标识：PROMPT_OPTIMIZE=提示词优化 */
        String scene,
        /** 业务目标模型（如被优化的视频模型）；纯 LLM 场景可为 null */
        String targetModel
) {
    public static final String SCENE_PROMPT_OPTIMIZE = "PROMPT_OPTIMIZE";
}
