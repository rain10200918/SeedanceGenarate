package org.example.seedancegenarate.dto;

/**
 * 管理员「模型开放管理」列表项：模型基本信息 + 当前开关态。
 * 模型集合来自 VideoEngineRegistry，{@code open} 叠加自 model_access 覆盖表（无行走默认）。
 */
public record ModelAccessView(
        String provider,
        String model,
        String label,
        String outputType,
        boolean open
) {
}
