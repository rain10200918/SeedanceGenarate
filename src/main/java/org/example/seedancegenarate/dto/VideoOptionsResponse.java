package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * GET /api/video/options 响应：可选提供方及其模型能力，驱动前端的提供方 / 模型 / 比例 / 时长选择器。
 */
public record VideoOptionsResponse(
        String defaultProvider,
        List<ProviderOption> providers
) {
    /** 一个提供方及其可选模型 */
    public record ProviderOption(
            String provider,
            String label,
            List<ModelOption> models
    ) {
    }

    /** 一个模型的能力 / 约束（durations 已解析成离散可选值供前端直接渲染） */
    public record ModelOption(
            String model,
            String label,
            boolean needImages,
            int imageMin,
            int imageMax,
            List<String> ratios,
            List<Integer> durations,
            String outputType,
            List<Double> megapixels,
            boolean open,
            int videoMax,
            int audioMax,
            boolean needImageOrVideo
    ) {
    }
}
