package org.example.seedancegenarate.service;

import org.example.seedancegenarate.service.llm.LlmChatResponse;

public interface PromptOptimizeService {
    /**
     * 调用大模型优化生成提示词，返回优化后的提示词。
     * 按 {@link PromptContext#model()} 选择提示词模板（缺失则用默认模板）；用哪条 LLM 通道由路由决定。
     */
    String optimize(String prompt, PromptContext context) throws Exception;

    /**
     * 管理端试跑：<b>指定通道</b>（含停用/归档）跑同一套模板，返回内容 + token。
     * 切换通道前用固定样例肉眼对比输出——换 LLM 不会报错，只会让输出悄悄变差，测试测不出来。
     */
    LlmChatResponse optimizeWith(String channelName, String prompt, PromptContext context);
}
