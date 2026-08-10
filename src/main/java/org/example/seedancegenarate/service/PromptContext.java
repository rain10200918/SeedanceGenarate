package org.example.seedancegenarate.service;

/**
 * 提示词优化上下文：来自当前生成参数，用于按 model 选择模板并注入占位（如 {@code {imageCount}}）。
 * 字段可为空，模板按需引用。
 */
public record PromptContext(
        String model,
        Integer imageCount,
        Integer videoCount,
        Integer audioCount,
        Integer duration,
        String ratio
) {
}
