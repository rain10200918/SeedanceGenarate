package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.dto.ModelPricingView;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicModelPricingServiceTest {

    private VideoEngineRegistry registry;
    private ModelAccessService modelAccessService;
    private PricingService pricingService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private PublicModelPricingServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(VideoEngineRegistry.class);
        modelAccessService = mock(ModelAccessService.class);
        pricingService = mock(PricingService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();

        service = new PublicModelPricingServiceImpl(
                registry, modelAccessService, pricingService, redisTemplate, objectMapper
        );
    }

    @Test
    void hitsRedisCacheWhenPresent() {
        String cachedJson = "[{\"model\":\"test-model\",\"label\":\"Test Model\",\"pointsPerUnit\":20}]";
        when(valueOperations.get(PublicModelPricingServiceImpl.CACHE_KEY)).thenReturn(cachedJson);

        List<ModelPricingView> result = service.getPublicModels(false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getModel()).isEqualTo("test-model");
        assertThat(result.get(0).getPointsPerUnit()).isEqualTo(20L);
        verify(registry).all(); // 能力按本实例声明装配；价格仍走缓存。
        verifyNoInteractions(pricingService);
    }

    @Test
    void populatesRedisCacheOnMiss() {
        when(valueOperations.get(PublicModelPricingServiceImpl.CACHE_KEY)).thenReturn(null);

        VideoEngine engine = mock(VideoEngine.class);
        when(engine.provider()).thenReturn("seedance");
        when(engine.displayName()).thenReturn("Seedance 引擎");
        ModelSpec spec = new ModelSpec("seedance", "minimax-h3", "MiniMax H3", true, 1, 1,
                List.of("16:9"), 5, 10, List.of(5, 8, 10), OutputType.VIDEO, List.of(1.0), 0, 0, false);
        when(engine.models()).thenReturn(List.of(spec));
        when(registry.all()).thenReturn(List.of(engine));

        when(modelAccessService.currentOverrides()).thenReturn(Map.of());
        when(modelAccessService.defaultOpen()).thenReturn(true);

        PricingService.ModelPriceInfo priceInfo = new PricingService.ModelPriceInfo(
                BigDecimal.valueOf(0.20), "PER_SECOND", 20L, "CNY", "20 算力点 / 秒"
        );
        when(pricingService.getModelPriceInfo("seedance", "minimax-h3", OutputType.VIDEO))
                .thenReturn(priceInfo);

        List<ModelPricingView> result = service.getPublicModels(false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getModel()).isEqualTo("minimax-h3");
        assertThat(result.get(0).getPointsPerUnit()).isEqualTo(20L);
        assertThat(result.get(0).getPricingText()).isEqualTo("20 算力点 / 秒");

        verify(valueOperations).set(eq(PublicModelPricingServiceImpl.CACHE_KEY), anyString(), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void clearCacheDeletesRedisKey() {
        service.clearCache();
        verify(redisTemplate).delete(PublicModelPricingServiceImpl.CACHE_KEY);
    }

    // 【测什么】24小时旧缓存也带当前档位；新实例写回仍为旧形状，旧实例可反序列化。
    // 【怎么算红】直接返回旧缓存或把新能力写入共享payload时断言失败。
    @Test void enrichesOldCacheAndKeepsWrittenPayloadCompatible() throws Exception {
        var spec=new org.example.seedancegenarate.engine.comfyui.Impl.Flux2ImageEditWorkflowBuilder(objectMapper).spec();
        var engine=mock(VideoEngine.class);
        when(engine.models()).thenReturn(List.of(spec)); when(engine.provider()).thenReturn("comfyui");
        when(registry.all()).thenReturn(List.of(engine));
        when(valueOperations.get(PublicModelPricingServiceImpl.CACHE_KEY))
                .thenReturn("[{\"provider\":\"comfyui\",\"model\":\"flux2-image-edit\",\"pointsPerUnit\":20}]");
        var cached=service.getPublicModels(false).get(0);
        assertThat(cached.getResolutions()).hasSize(3); assertThat(cached.getDefaultResolution()).isEqualTo("720p");
        assertThat(cached.getPointsPerUnit()).isEqualTo(20L); verifyNoInteractions(pricingService);
        when(valueOperations.get(PublicModelPricingServiceImpl.CACHE_KEY)).thenReturn(null);
        when(modelAccessService.defaultOpen()).thenReturn(true); when(modelAccessService.currentOverrides()).thenReturn(Map.of());
        when(pricingService.getModelPriceInfo(any(),any(),any())).thenReturn(
                new PricingService.ModelPriceInfo(BigDecimal.ONE,"FLAT",100L,"CNY","100"));
        assertThat(service.getPublicModels(false).get(0).getResolutions()).hasSize(3);
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(PublicModelPricingServiceImpl.CACHE_KEY),capture.capture(),eq(24L),eq(TimeUnit.HOURS));
        var row=objectMapper.readTree(capture.getValue()).get(0);
        assertThat(row.has("resolutions")).isFalse(); assertThat(row.has("defaultResolution")).isFalse();
    }
}
