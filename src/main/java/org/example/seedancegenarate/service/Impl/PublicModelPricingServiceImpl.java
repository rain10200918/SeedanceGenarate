package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.dto.ModelPricingView;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.PublicModelPricingService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * 用户端模型与算力定价服务实现：
 * 采用 Cache-Aside 模式存储在 Redis 中，管理员修改定价或模型开放状态时主动驱逐缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicModelPricingServiceImpl implements PublicModelPricingService {

    public static final String CACHE_KEY = "seedance:cache:public_model_pricing";

    private final VideoEngineRegistry videoEngineRegistry;
    private final ModelAccessService modelAccessService;
    private final PricingService pricingService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    public void init() {
        // 服务启动/发版时主动清理一次 Redis 缓存，确保新上线的模型在后端重启后立即向用户呈现
        clearCache();
    }

    @Override
    public List<ModelPricingView> getPublicModels(boolean includeClosed) {
        // 普通用户只看公开可用模型：优先走 Redis 缓存
        if (!includeClosed) {
            try {
                String cached = redisTemplate.opsForValue().get(CACHE_KEY);
                if (StringUtils.hasText(cached)) {
                    List<ModelPricingView> cachedList = objectMapper.readValue(cached, new TypeReference<List<ModelPricingView>>() {});
                    log.info("命中 Redis 模型定价缓存: key={}, 模型数量={}", CACHE_KEY, cachedList != null ? cachedList.size() : 0);
                    return cachedList;
                }
                log.info("未命中 Redis 模型定价缓存 (Cache Miss)，将执行实时计算并回填: key={}", CACHE_KEY);
            } catch (Exception e) {
                log.warn("读取 Redis 模型定价缓存失败，回退实时计算: err={}", e.getMessage());
            }
        }

        // 缓存未命中或管理员查看全量：实时汇总
        List<ModelPricingView> list = computeModelPricingList(includeClosed);

        // 普通用户查询结果回填 Redis（TTL 24 小时，等待下一次管理员修改或过期）
        if (!includeClosed) {
            try {
                String json = objectMapper.writeValueAsString(list);
                redisTemplate.opsForValue().set(CACHE_KEY, json, 24, TimeUnit.HOURS);
                log.info("已生成并写入 Redis 模型定价缓存: key={}, count={}", CACHE_KEY, list.size());
            } catch (Exception e) {
                log.warn("写入 Redis 模型定价缓存失败: err={}", e.getMessage());
            }
        }

        return list;
    }

    @Override
    public void clearCache() {
        try {
            Boolean deleted = redisTemplate.delete(CACHE_KEY);
            log.info("已清除 Redis 模型定价缓存: key={}, deleted={}", CACHE_KEY, deleted);
        } catch (Exception e) {
            log.warn("清除 Redis 模型定价缓存异常: key={}, err={}", e.getMessage());
        }
    }

    private List<ModelPricingView> computeModelPricingList(boolean includeClosed) {
        Map<String, Boolean> overrides = modelAccessService.currentOverrides();
        boolean defaultOpen = modelAccessService.defaultOpen();

        List<ModelPricingView> result = new ArrayList<>();
        videoEngineRegistry.all().forEach(engine -> {
            String provider = engine.provider();
            String providerName = engine.displayName();

            for (ModelSpec spec : engine.models()) {
                boolean isOpen = overrides.getOrDefault(spec.model(), defaultOpen);
                if (!includeClosed && !isOpen) {
                    continue;
                }

                PricingService.ModelPriceInfo priceInfo = pricingService.getModelPriceInfo(
                        provider, spec.model(), spec.outputType()
                );

                List<Integer> durations;
                if (!spec.durations().isEmpty()) {
                    durations = spec.durations();
                } else if (spec.durationMax() >= spec.durationMin() && spec.durationMax() > 0) {
                    durations = IntStream.rangeClosed(spec.durationMin(), spec.durationMax()).boxed().toList();
                } else {
                    durations = List.of();
                }

                List<String> tags = generateTags(spec, priceInfo);

                ModelPricingView view = ModelPricingView.builder()
                        .model(spec.model())
                        .label(spec.label())
                        .provider(provider)
                        .providerName(providerName)
                        .outputType(spec.outputType().name())
                        .billingType(priceInfo.billingType())
                        .unitPrice(priceInfo.unitPrice())
                        .pointsPerUnit(priceInfo.pointsPerUnit())
                        .pricingText(priceInfo.pricingText())
                        .ratios(spec.ratios())
                        .durations(durations)
                        .megapixels(spec.megapixels())
                        .imageMin(spec.imageMin())
                        .imageMax(spec.imageMax())
                        .needImages(spec.needImages())
                        .videoMax(spec.videoMax())
                        .audioMax(spec.audioMax())
                        .needImageOrVideo(spec.needImageOrVideo())
                        .open(isOpen)
                        .description(generateDescription(spec))
                        .tags(tags)
                        .build();

                result.add(view);
            }
        });

        result.sort(Comparator.comparing(ModelPricingView::getProvider)
                .thenComparing(ModelPricingView::getModel));
        return result;
    }

    private List<String> generateTags(ModelSpec spec, PricingService.ModelPriceInfo priceInfo) {
        List<String> tags = new ArrayList<>();
        if ("IMAGE".equals(spec.outputType().name())) {
            tags.add("图片生成/编辑");
        } else {
            tags.add("高清视频");
        }
        if ("FLAT".equals(priceInfo.billingType())) {
            tags.add("固定计费");
        } else {
            tags.add("时长计费");
        }
        if (spec.videoMax() > 0) {
            tags.add("视频参考");
        }
        if (spec.imageMax() > 1) {
            tags.add("多图参考");
        }
        return tags;
    }

    private String generateDescription(ModelSpec spec) {
        if ("flux2-image-edit".equals(spec.model())) {
            return "基于 FLUX.2 的前沿多模态图像重绘与多图创意编辑，支持高精度构图与元素重塑。";
        }
        if ("qwen-image-edit".equals(spec.model())) {
            return "通义千问专业图像编辑大模型，支持精准单图局部重绘与风格迁移。";
        }
        if ("z-image-turbo".equals(spec.model())) {
            return "超极速图像生成引擎，毫秒级即时出图。";
        }
        if ("minimax-h3-hd".equals(spec.model()) || "minimax-h3-fl2va-hd".equals(spec.model())) {
            return "MiniMax 4K 影视级超清视频生成，具备极高动态一致性与逼真光影质感。";
        }
        if (spec.model().contains("minimax")) {
            return "MiniMax 领先级视频大模型，运动幅度自然饱满，语义遵循度优异。";
        }
        if (spec.model().contains("kling")) {
            return "快手可灵视频大模型，支持高物理真实度的复杂动作与运镜生成。";
        }
        if (spec.model().contains("seedance")) {
            return "Seedance 官方高性能视频生成大模型，稳定极速。";
        }
        return spec.label() + " 专业级 AI 生成与渲染服务。";
    }
}
