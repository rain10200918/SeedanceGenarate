package org.example.seedancegenarate.canvas;

/**
 * 端口上流动的数据类型。连线合法性 = 上游输出类型 ∈ 下游端口 accepts。
 * <p>
 * 注意 AUDIO 目前只能作为输入：平台的 {@code OutputType} 只有 VIDEO / IMAGE，
 * 没有产出音频的模型，所以音频只能来自素材节点。
 */
public enum MediaType {
    IMAGE, VIDEO, AUDIO, TEXT
}
