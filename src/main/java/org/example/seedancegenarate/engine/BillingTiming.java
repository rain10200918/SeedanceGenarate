package org.example.seedancegenarate.engine;

/**
 * 计费时机。由各 {@link VideoEngine} 声明，计费逻辑据此决定何时落账：
 * <ul>
 *   <li>{@link #ON_SUBMIT}：提交即计费。云端 API（Seedance）接受任务即消耗额度，无论后续成败。</li>
 *   <li>{@link #ON_SUCCESS}：成功才计费。自建 ComfyUI 仅在生成成功时向用户计费。</li>
 * </ul>
 * 新增提供方只需在自己的引擎里覆写 {@link VideoEngine#billingTiming()}，计费调用点零改动。
 */
public enum BillingTiming {
    ON_SUBMIT,
    ON_SUCCESS
}
