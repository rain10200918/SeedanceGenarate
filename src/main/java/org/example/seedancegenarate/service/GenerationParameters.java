package org.example.seedancegenarate.service;

import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.exception.BusinessException;

import java.util.List;

/** 新请求的纯能力校验；不读取存储、不修改输入，也不用于重新解释历史任务。 */
public record GenerationParameters(int duration, String ratio, Double megapixels) {
    public static int resolveDuration(ModelSpec model, Integer duration) {
        if (model == null) throw BusinessException.badRequest("模型能力不可用");
        if (model.outputType() == OutputType.IMAGE) {
            // 旧UI显式传1、旧API默认8，均为无时长图片的历史占位，不代表产物时长。
            if (duration != null && duration != 1 && duration != 8)
                throw BusinessException.badRequest("图片生成不支持设置时长");
            return 8;
        }
        List<Integer> durations = model.durations() == null ? List.of() : model.durations();
        int seconds = duration == null ? 8 : duration;
        if (duration == null && !supportsDuration(model, durations, seconds)) {
            seconds = durations.isEmpty() ? Math.max(1, model.durationMin())
                    : durations.stream().filter(value -> value != null && value > 0)
                    .findFirst().orElseThrow(() -> BusinessException.badRequest("模型没有可用时长"));
        }
        if (!supportsDuration(model, durations, seconds))
            throw BusinessException.badRequest("时长不在模型支持范围内");
        return seconds;
    }

    public static GenerationParameters validate(ModelSpec model, Integer duration,
                                                 String ratio, Double megapixels,
                                                 int imageCount, int videoCount, int audioCount) {
        int seconds = resolveDuration(model, duration);
        List<String> ratios = model.ratios() == null ? List.of() : model.ratios();
        String resolvedRatio = ratio;
        if (ratio == null || ratio.isBlank()) {
            resolvedRatio = ratios.contains("16:9") ? "16:9" : ratios.stream().findFirst().orElse(null);
        } else if (!ratios.contains(ratio)) {
            throw BusinessException.badRequest("画幅不在模型支持范围内");
        }
        if (megapixels != null && (!Double.isFinite(megapixels)
                || model.megapixels() == null || !model.megapixels().contains(megapixels)))
            throw BusinessException.badRequest("分辨率不在模型支持范围内");
        int imageMin = Math.max(model.imageMin(), model.needImages() ? 1 : 0);
        if (imageCount < 0 || imageCount < imageMin || imageCount > model.imageMax())
            throw BusinessException.badRequest("参考图片数量不在模型支持范围内");
        if (videoCount < 0 || videoCount > model.videoMax())
            throw BusinessException.badRequest("参考视频数量不在模型支持范围内");
        if (audioCount < 0 || audioCount > model.audioMax())
            throw BusinessException.badRequest("参考音频数量不在模型支持范围内");
        if (model.needImageOrVideo() && imageCount == 0 && videoCount == 0)
            throw BusinessException.badRequest("该模型至少需要一张参考图片或一个参考视频");
        return new GenerationParameters(seconds, resolvedRatio, megapixels);
    }

    private static boolean supportsDuration(ModelSpec model, List<Integer> durations, int seconds) {
        return seconds > 0 && (durations.isEmpty()
                ? seconds >= model.durationMin() && seconds <= model.durationMax()
                : durations.contains(seconds));
    }
}
