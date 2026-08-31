package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.PriceConfig;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.mapper.PriceConfigMapper;
import org.example.seedancegenarate.service.ConfigInvalidationNotifier;
import org.example.seedancegenarate.service.Impl.ConfigPricingService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端模型计价配置（在线改价）。
 * 计费时三级回退（模型精确 → 提供方默认 → yaml），未配置的模型沿用 yaml 默认价。
 */
@RestController
@RequestMapping("/api/admin/pricing")
@RequiredArgsConstructor
public class AdminPricingController {

    private final PriceConfigMapper priceConfigMapper;
    private final VideoEngineRegistry videoEngineRegistry;
    private final ConfigPricingService configPricingService;
    private final ConfigInvalidationNotifier invalidationNotifier;
    private final org.example.seedancegenarate.service.PublicModelPricingService publicModelPricingService;

    @GetMapping
    public Result<List<PriceConfig>> list() {
        requireAdmin();
        return Result.success(priceConfigMapper.selectList(
                Wrappers.<PriceConfig>lambdaQuery()
                        .orderByAsc(PriceConfig::getProvider)
                        .orderByAsc(PriceConfig::getModel)));
    }

    @PostMapping
    public Result<PriceConfig> create(@RequestBody PriceConfig config) {
        requireAdmin();
        validate(config);
        config.setId(null);
        priceConfigMapper.insert(config);
        refreshPricing();
        return Result.success(config);
    }

    @PutMapping("/{id}")
    public Result<PriceConfig> update(@PathVariable Long id, @RequestBody PriceConfig config) {
        requireAdmin();
        validate(config);
        config.setId(id);
        priceConfigMapper.updateById(config);
        refreshPricing();
        return Result.success(priceConfigMapper.selectById(id));
    }

    /** 删除配置：该模型回退到 yaml 默认价 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireAdmin();
        priceConfigMapper.deleteById(id);
        refreshPricing();
        return Result.<Void>success(null);
    }

    /** 先刷本实例与清除 Redis 定价缓存，再广播给其他实例。 */
    private void refreshPricing() {
        configPricingService.reload();
        publicModelPricingService.clearCache();
        invalidationNotifier.notifyChanged(ConfigInvalidationNotifier.TYPE_PRICING);
    }

    private void validate(PriceConfig config) {
        if (config == null || !StringUtils.hasText(config.getProvider())) {
            throw new RuntimeException("提供方不能为空");
        }
        if (!"PER_SECOND".equals(config.getBillingType()) && !"FLAT".equals(config.getBillingType())) {
            throw new RuntimeException("计费方式只能是 PER_SECOND 或 FLAT");
        }
        // 生图模型无时长概念，只能按次计费（与计价兜底双保险）
        if (StringUtils.hasText(config.getModel())) {
            OutputType outputType = videoEngineRegistry.get(config.getProvider()).outputType(config.getModel());
            if (outputType == OutputType.IMAGE && !"FLAT".equals(config.getBillingType())) {
                throw new RuntimeException("生图模型无时长概念，只能按次计费（FLAT）");
            }
        }
        if (config.getUnitPrice() == null || config.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("单价不能为负");
        }
        if (config.getModel() == null) {
            config.setModel("");
        }
        if (config.getCurrency() == null || config.getCurrency().isBlank()) {
            config.setCurrency("CNY");
        }
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
