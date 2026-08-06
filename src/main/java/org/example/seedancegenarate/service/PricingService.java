package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;

import java.math.BigDecimal;

/**
 * 计费策略：按提供方计算单次生成的单价与金额。
 * <p>
 * 当前由配置驱动（{@code ConfigPricingService}）：Seedance 按秒、ComfyUI 按次固定价。
 * 后续如需管理员在线改价，替换为读数据库的实现即可，调用点不变。
 */
public interface PricingService {

    Price price(VideoTask task);

    /** 计费结果：单价 + 金额 + 币种 */
    record Price(BigDecimal unitPrice, BigDecimal amount, String currency) {
    }
}
