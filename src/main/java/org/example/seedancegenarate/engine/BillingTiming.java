package org.example.seedancegenarate.engine;

/**
 * 用户计费时机。当前所有提供方统一采用 {@link #ON_SUCCESS}：提交只冻结，成功结算，失败释放。
 * {@link #ON_SUBMIT} 保留为兼容枚举值，但不应再用于用户消费账务。
 * 新增提供方默认继承 {@link VideoEngine#billingTiming()}，无需自行决定用户扣费时机。
 */
public enum BillingTiming {
    ON_SUBMIT,
    ON_SUCCESS
}
