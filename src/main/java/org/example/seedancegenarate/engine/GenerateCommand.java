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
    /** 事件驱动引擎的回调地址（框架注入，带鉴权 token）；轮询引擎忽略 */
    private String webhookUrl;
    /**
     * 指定跑在哪台 ComfyUI 节点上（<b>管理员专用</b>，为空则正常调度）。
     * <p>
     * 用途只有一个：一台新机器接进来之后，在<b>不放量</b>的前提下先把真实工作流跑通。
     * 所以它必须能指到 {@code enabled=false} 的节点上 —— 那正是新机器的初始状态。
     * 其它引擎（Seedance）忽略这个字段。
     */
    private String nodeId;
}
