package org.example.seedancegenarate.service.Impl;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.PricingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 配置驱动的计费实现：按提供方分别计价。
 * <ul>
 *   <li>Seedance：按秒计费（单价 × 时长）</li>
 *   <li>ComfyUI：按次固定价（默认 0.01 元/次，与时长无关）</li>
 * </ul>
 * 单价、币种均来自配置；未知提供方按 0 计（避免误扣）。
 */
@Slf4j
@Service
public class ConfigPricingService implements PricingService {

    @Value("${billing.seedance.price-per-second:0.20}")
    private BigDecimal seedancePricePerSecond;

    @Value("${billing.seedance.currency:CNY}")
    private String seedanceCurrency;

    @Value("${billing.comfyui.flat-price:0.01}")
    private BigDecimal comfyuiFlatPrice;

    @Value("${billing.comfyui.currency:CNY}")
    private String comfyuiCurrency;

    @Override
    public Price price(VideoTask task) {
        String provider = (task == null || task.getProvider() == null) ? "seedance" : task.getProvider();
        int duration = (task == null || task.getDuration() == null) ? 0 : task.getDuration();
        switch (provider) {
            case "comfyui" -> {
                BigDecimal amount = comfyuiFlatPrice.setScale(2, RoundingMode.HALF_UP);
                return new Price(comfyuiFlatPrice, amount, comfyuiCurrency);
            }
            case "seedance" -> {
                BigDecimal amount = seedancePricePerSecond
                        .multiply(BigDecimal.valueOf(duration))
                        .setScale(2, RoundingMode.HALF_UP);
                return new Price(seedancePricePerSecond, amount, seedanceCurrency);
            }
            default -> {
                log.warn("未知计费提供方 provider={}，本次不计费", provider);
                return new Price(BigDecimal.ZERO, BigDecimal.ZERO, seedanceCurrency);
            }
        }
    }
}
