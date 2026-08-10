package org.example.seedancegenarate.engine;

import java.util.List;

/**
 * 模型能力 / 参数约束描述。供 /options 接口下发前端、后端校验共用。
 * <p>
 * {@code durations} 为空时表示可用时长由连续区间 [{@code durationMin}, {@code durationMax}] 决定；
 * 非空时为离散可选值（如 Seedance 的 5/8/10 秒）。
 * <p>
 * {@code outputType} 标记产物是视频还是图片，驱动前端渲染方式；旧的 10 参构造器默认 {@link OutputType#VIDEO}，
 * 故已有的视频 builder 无需改动。
 * <p>
 * {@code videoMax} / {@code audioMax} 声明模型支持多少个参考视频 / 参考音频（0 = 不支持），
 * {@code needImageOrVideo} 声明「图片或视频参考至少一个」（用于多参考生视频模型，音频单独不算）；
 * 三者默认 0 / 0 / false，已有 builder 无需改动。
 */
public record ModelSpec(
        String provider,
        String model,
        String label,
        boolean needImages,
        int imageMin,
        int imageMax,
        List<String> ratios,
        int durationMin,
        int durationMax,
        List<Integer> durations,
        OutputType outputType,
        List<Double> megapixels,
        int videoMax,
        int audioMax,
        boolean needImageOrVideo
) {
    /** 兼容：不指定 outputType（默认视频）、不支持分辨率选择（megapixels 空）、不支持视频/音频参考。 */
    public ModelSpec(String provider, String model, String label, boolean needImages,
                     int imageMin, int imageMax, List<String> ratios,
                     int durationMin, int durationMax, List<Integer> durations) {
        this(provider, model, label, needImages, imageMin, imageMax, ratios,
                durationMin, durationMax, durations, OutputType.VIDEO, List.of(), 0, 0, false);
    }

    /** 兼容：指定 outputType，但不支持分辨率选择（megapixels 空）、不支持视频/音频参考。 */
    public ModelSpec(String provider, String model, String label, boolean needImages,
                     int imageMin, int imageMax, List<String> ratios,
                     int durationMin, int durationMax, List<Integer> durations,
                     OutputType outputType) {
        this(provider, model, label, needImages, imageMin, imageMax, ratios,
                durationMin, durationMax, durations, outputType, List.of(), 0, 0, false);
    }

    /** 兼容：指定 outputType + 分辨率档位（megapixels），但不支持视频/音频参考。 */
    public ModelSpec(String provider, String model, String label, boolean needImages,
                     int imageMin, int imageMax, List<String> ratios,
                     int durationMin, int durationMax, List<Integer> durations,
                     OutputType outputType, List<Double> megapixels) {
        this(provider, model, label, needImages, imageMin, imageMax, ratios,
                durationMin, durationMax, durations, outputType, megapixels, 0, 0, false);
    }
}
