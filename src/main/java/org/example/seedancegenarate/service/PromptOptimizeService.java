package org.example.seedancegenarate.service;

public interface PromptOptimizeService {
    /**
     * 调用大模型优化生成提示词，返回优化后的提示词。
     * 按 {@link PromptContext#model()} 选择提示词模板（缺失则用默认模板）。
     */
    String optimize(String prompt, PromptContext context) throws Exception;
}
