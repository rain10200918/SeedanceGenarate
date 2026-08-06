package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 文生视频请求参数
 */
@Data
public class TextToVideoRequest {
    /**
     * 提示词
     */
    private String prompt;
    /**
     * 视频时长（秒）
     */
    private Integer duration;
    /**
     * 画面比例
     */
    private String ratio;
    /**
     * 生成提供方（seedance / comfyui...）；为空则用后端默认
     */
    private String provider;
    /**
     * 模型 / 工作流标识（ComfyUI 必填，Seedance 可忽略）
     */
    private String model;
}
