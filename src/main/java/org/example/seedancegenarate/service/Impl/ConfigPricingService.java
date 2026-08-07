package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.PriceConfig;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.PriceConfigMapper;
import org.example.seedancegenarate.service.PricingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计费实现：数据库配置优先，yaml 兜底（三级回退，平滑迁移，无需初始化数据）。
 * <ol>
 *   <li>DB：{@code price_config} 按 (provider, model) 精确匹配</li>
 *   <li>DB：该提供方的默认价（model 为空串）</li>
 *   <li>yaml：现有默认（Seedance 按秒 0.20 元/秒、ComfyUI 按次 0.01 元/次）</li>
 * </ol>
 * 未知提供方按 0 计（避免误扣）。管理端改价入口见 {@code AdminPricingController}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigPricingService implements PricingService {

    private final PriceConfigMapper priceConfigMapper;

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

        // ① 模型精确配置 → ② 提供方默认配置（model 为空串）
        String model = (task == null || task.getModel() == null) ? "" : task.getModel();
        PriceConfig config = findEnabled(provider, model);
        if (config == null) {
            config = findEnabled(provider, "");
        }
        if (config != null) {
            return priceFromConfig(config, duration, task);
        }

        // ③ yaml 默认
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

    /**
     * 按数据库配置计价：PER_SECOND 单价×时长，FLAT 固定价。
     * 生图模型无时长概念：即使配置被误设为按秒，也强制按次计价（防御兜底）。
     */
    private Price priceFromConfig(PriceConfig config, int duration, VideoTask task) {
        BigDecimal unit = config.getUnitPrice() == null ? BigDecimal.ZERO : config.getUnitPrice();
        String currency = config.getCurrency() == null ? "CNY" : config.getCurrency();
        boolean isImage = task != null && "IMAGE".equals(task.getOutputType());
        if ("PER_SECOND".equals(config.getBillingType()) && !isImage) {
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(duration)).setScale(2, RoundingMode.HALF_UP);
            return new Price(unit, amount, currency);
        }
        return new Price(unit, unit.setScale(2, RoundingMode.HALF_UP), currency);
    }

    private PriceConfig findEnabled(String provider, String model) {
        return priceConfigMapper.selectOne(Wrappers.<PriceConfig>lambdaQuery()
                .eq(PriceConfig::getProvider, provider)
                .eq(PriceConfig::getModel, model)
                .eq(PriceConfig::getEnabled, true)
                .last("limit 1"));
    }
}
