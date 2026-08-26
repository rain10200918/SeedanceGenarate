package org.example.seedancegenarate.canvas;

import java.util.List;

/**
 * 一个可执行节点的提交计划：由 {@code CanvasNodeType} 从「节点配置 + 各端口解析出的上游产物」
 * 组装而成，最终交给既有的 {@code VideoSubmitService} —— 钱和提交链路一行不改。
 */
public record SubmitPlan(
        String provider,
        String model,
        String prompt,
        List<String> imageUrls,
        List<String> videoUrls,
        List<String> audioUrls,
        Integer duration,
        String ratio,
        Double megapixels) {
}
