package org.example.seedancegenarate.service;

import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GenerationParametersTest {
    private ModelSpec model(OutputType type, List<Integer> durations, int min, int max,
                            List<String> ratios, List<Double> resolutions) {
        return new ModelSpec("test", "model", "Model", false, 0, 2,
                ratios, min, max, durations, type, resolutions, 1, 1, false);
    }

    // 【测什么】离散空洞拒绝，缺省优先8，否则按公布顺序取首合法时长。
    // 【怎么算红】改成区间校验或无条件默认8，本测试失败。
    @Test void discreteDurationAndDefaults() {
        var spec = model(OutputType.VIDEO, List.of(5, 8, 10), 5, 10, List.of(), List.of());
        assertEquals(8, GenerationParameters.resolveDuration(spec, null));
        for (int invalid : new int[]{0, -1, 6, 9, 11})
            bad(() -> GenerationParameters.resolveDuration(spec, invalid));
        assertEquals(5, GenerationParameters.resolveDuration(spec, 5));
        assertEquals(10, GenerationParameters.resolveDuration(spec, 10));
        assertEquals(10, GenerationParameters.resolveDuration(
                model(OutputType.VIDEO, List.of(10, 5), 5, 10, List.of(), List.of()), null));
    }

    // 【测什么】连续区间含端点，音频默认合法下限；图片兼容1/8占位统一为8。
    // 【怎么算红】端点排除、音频默认8、拒绝图片1或接受图片5，本测试失败。
    @Test void continuousAudioAndImageDuration() {
        var audio = model(OutputType.AUDIO, List.of(), 30, 300, List.of(), List.of());
        assertEquals(30, GenerationParameters.resolveDuration(audio, null));
        assertEquals(300, GenerationParameters.resolveDuration(audio, 300));
        bad(() -> GenerationParameters.resolveDuration(audio, 29));
        bad(() -> GenerationParameters.resolveDuration(audio, 301));
        var image = model(OutputType.IMAGE, List.of(), 0, 0, List.of(), List.of());
        assertEquals(8, GenerationParameters.resolveDuration(image, null));
        assertEquals(8, GenerationParameters.resolveDuration(image, 8));
        assertEquals(8, GenerationParameters.resolveDuration(image, 1));
        bad(() -> GenerationParameters.resolveDuration(image, 5));
    }

    // 【测什么】比例按模型默认，无能力时显式值拒绝；分辨率有限且必须在档位内。
    // 【怎么算红】静默丢弃显式比例或放过NaN/未公布分辨率，本测试失败。
    @Test void ratiosAndResolutions() {
        var spec = model(OutputType.VIDEO, List.of(8), 8, 8,
                List.of("1:1", "16:9"), List.of(0.9, 1.2));
        assertEquals(new GenerationParameters(8, "16:9", null),
                GenerationParameters.validate(spec, null, " ", null, 0, 0, 0));
        assertEquals(1.2, GenerationParameters.validate(spec, 8, "1:1", 1.2, 0, 0, 0).megapixels());
        bad(() -> GenerationParameters.validate(spec, 8, "4:3", null, 0, 0, 0));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0, -1, 1.0})
            bad(() -> GenerationParameters.validate(spec, 8, null, invalid, 0, 0, 0));
        var none = model(OutputType.AUDIO, List.of(30), 30, 30, List.of(), List.of());
        assertNull(GenerationParameters.validate(none, null, null, null, 0, 0, 0).ratio());
        bad(() -> GenerationParameters.validate(none, null, "16:9", null, 0, 0, 0));
        bad(() -> GenerationParameters.validate(none, null, null, 0.9, 0, 0, 0));
        var square = model(OutputType.IMAGE, List.of(), 0, 0, List.of("1:1"), List.of());
        assertEquals("1:1", GenerationParameters.validate(square, null, null, null, 0, 0, 0).ratio());
    }

    // 【测什么】参考数量含上限，音频不能替代视觉参考，必须图片时视频不能替代。
    // 【怎么算红】去掉任一数量/最低参考守卫，本测试对应非法组合不再400。
    @Test void mediaBoundsAndRequiredVisualInput() {
        var spec = new ModelSpec("test", "multi", "Multi", false, 0, 2, List.of(),
                8, 8, List.of(8), OutputType.VIDEO, List.of(), 1, 1, true);
        GenerationParameters.validate(spec, 8, null, null, 2, 1, 1);
        GenerationParameters.validate(spec, 8, null, null, 0, 1, 0);
        for (int[] counts : new int[][]{{0,0,1}, {3,0,0}, {1,2,0}, {1,0,2}, {-1,1,0}, {1,-1,0}, {1,0,-1}})
            bad(() -> GenerationParameters.validate(spec, 8, null, null, counts[0], counts[1], counts[2]));
        var image = new ModelSpec("test", "image", "Image", true, 2, 2, List.of(),
                8, 8, List.of(8), OutputType.VIDEO, List.of(), 1, 0, false);
        bad(() -> GenerationParameters.validate(image, 8, null, null, 1, 1, 0));
        GenerationParameters.validate(image, 8, null, null, 2, 0, 0);
        var required = new ModelSpec("test", "required", "Required", true, 0, 2,
                List.of(), 8, 8, List.of(8));
        bad(() -> GenerationParameters.validate(required, 8, null, null, 0, 0, 0));
    }

    private void bad(org.junit.jupiter.api.function.Executable action) {
        assertEquals(400, assertThrows(BusinessException.class, action).getCode());
    }
}
