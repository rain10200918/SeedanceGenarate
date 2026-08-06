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
        Integer duration,
        String ratio,
        Double megapixels
) {
}
