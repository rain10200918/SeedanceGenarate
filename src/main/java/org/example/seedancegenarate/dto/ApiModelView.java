package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 对外 API 的模型清单项（GET /api/v1/models）：能力约束与 UI /options 同源（ModelSpec），
 * 开关过滤同规则（关闭的不下发；属主是管理员则全部可见带 open 标记）。
 */
public record ApiModelView(
        String model,
        String label,
        String provider,
        String outputType,
        boolean needImages,
        int imageMin,
        int imageMax,
        List<String> ratios,
        List<Integer> durations,
        List<Double> megapixels,
        boolean open,
        int videoMax,
        int audioMax,
        boolean needImageOrVideo,
        String imageInputMode,
        List<org.example.seedancegenarate.engine.ModelSpec.ResolutionOption> resolutions,
        String defaultResolution
) {
    public ApiModelView(String model,String label,String provider,String outputType,boolean needImages,
                        int imageMin,int imageMax,List<String> ratios,List<Integer> durations,List<Double> megapixels,
                        boolean open,int videoMax,int audioMax,boolean needImageOrVideo,String imageInputMode) {
        this(model,label,provider,outputType,needImages,imageMin,imageMax,ratios,durations,megapixels,open,
                videoMax,audioMax,needImageOrVideo,imageInputMode,List.of(),null);
    }
    /** 保留原 Java 调用方；未声明的图片角色与 ModelSpec 兼容构造器一致。 */
    public ApiModelView(String model, String label, String provider, String outputType,
                        boolean needImages, int imageMin, int imageMax, List<String> ratios,
                        List<Integer> durations, List<Double> megapixels, boolean open) {
        this(model, label, provider, outputType, needImages, imageMin, imageMax,
                ratios, durations, megapixels, open, 0, 0, false,
                imageMax == 0 ? "NONE" : "UNSPECIFIED");
    }
}
