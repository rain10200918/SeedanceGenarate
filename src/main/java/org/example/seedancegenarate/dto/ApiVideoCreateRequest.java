package org.example.seedancegenarate.dto;

import java.util.List;

/**
 * 提交生成任务请求（POST /api/v1/videos）。
 * 模型标识 = 注册表全局 id（seedance / minimax-h3 / z-image-turbo ...），由后端自动定位提供方。
 */
public record ApiVideoCreateRequest(
        String prompt,
        String model,
        /** 参考图 URL 列表（图生视频/图生图用）；后端下载后转存 OSS */
        List<String> images,
        /** 参考视频 URL 列表；后端下载后转存 OSS */
        List<String> videos,
        /** 参考音频 URL 列表；后端下载后转存 OSS */
        List<String> audios,
        Integer duration,
        String ratio,
        Double megapixels,
        String resolution
) {
    public ApiVideoCreateRequest(String prompt,String model,List<String> images,List<String> videos,
                                 List<String> audios,Integer duration,String ratio,Double megapixels) {
        this(prompt,model,images,videos,audios,duration,ratio,megapixels,null);
    }
    /** 兼容 Java 调用方原有的图片生成构造器；JSON 请求使用完整字段。 */
    public ApiVideoCreateRequest(String prompt, String model, List<String> images,
                                 Integer duration, String ratio, Double megapixels) {
        this(prompt, model, images, List.of(), List.of(), duration, ratio, megapixels);
    }
}
