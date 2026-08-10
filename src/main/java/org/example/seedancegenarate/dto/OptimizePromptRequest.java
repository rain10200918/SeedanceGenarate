package org.example.seedancegenarate.dto;

import lombok.Data;

/**
 * 优化提示词请求
 */
@Data
public class OptimizePromptRequest {
    /**
     * 用户原始提示词
     */
    private String prompt;
    /** 目标模型（决定使用哪份提示词模板；缺失走默认） */
    private String model;
    /** 参考图张数（如 ref2va 用于生成 &lt;Picture 1..N&gt; 标签） */
    private Integer imageCount;
    /** 参考视频段数（多参考模型生成 &lt;Video 1..N&gt; 标签） */
    private Integer videoCount;
    /** 参考音频段数（多参考模型生成 &lt;Audio 1..N&gt; 标签） */
    private Integer audioCount;
    /** 视频时长（秒），供模板参考 */
    private Integer duration;
    /** 画面比例，供模板参考 */
    private String ratio;
}
