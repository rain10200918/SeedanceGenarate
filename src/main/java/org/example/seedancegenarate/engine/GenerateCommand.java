package org.example.seedancegenarate.engine;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 生成视频的统一入参，与具体提供方无关。
 */
@Data
@Builder
public class GenerateCommand {
    /** 生成模式：文生 / 图生 */
    private GenerationMode mode;
    /** 参考图地址（图生视频用；文生视频为空） */
    private List<String> imageUrls;
    /** 参考视频地址（多参考模型用；可为空） */
    private List<String> videoUrls;
    /** 参考音频地址（多参考模型用；可为空） */
    private List<String> audioUrls;
    /** 提示词 */
    private String prompt;
    /** 时长（秒） */
    private Integer duration;
    /** 画面比例 */
    private String ratio;
    /** 模型 / 工作流标识（ComfyUI 选择工作流用；Seedance 可为空） */
    private String model;
    /** 输出分辨率档位（百万像素，如 MiniMax-H3 加速版的 ResolutionSelector）；为空则用模型默认 */
    private Double megapixels;
}
