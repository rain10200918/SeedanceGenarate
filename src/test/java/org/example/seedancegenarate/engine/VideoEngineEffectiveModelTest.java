package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.SeedanceConfig;
import org.example.seedancegenarate.engine.Impl.SeedanceEngine;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.SeedanceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 纯单元测试：验证「实际生效模型」解析——模型开放闸门基于它而非请求原始参数，
 * 否则用户不传 model / 传任意 model 都能绕过闸门（回归：2026-08-05 实测绕过）。
 * <p>
 * Seedance 多模型化后不再覆写 {@code effectiveModel}（走默认实现）：
 * 单模型模式（未配 seedance.models）注册 id 仍是 "seedance"；多模型模式按配置列表解析。
 */
class VideoEngineEffectiveModelTest {

    private static final String API_MODEL = "doubao-seedance-2-0-260128";
    private static final String API_MODEL_FAST = "doubao-seedance-2-0-fast-260128";

    @Test
    void singleModelMode_defaultResolution_blankFallsBackToSeedance() {
        SeedanceEngine engine = singleModelEngine();
        assertEquals("seedance", engine.effectiveModel(null));
        assertEquals("seedance", engine.effectiveModel(""));
        assertEquals("seedance", engine.effectiveModel("   "));
    }

    @Test
    void singleModelMode_submit_mapsSeedanceIdToConfiguredApiModel() throws Exception {
        SeedanceService service = mock(SeedanceService.class);
        SeedanceEngine engine = new SeedanceEngine(service, singleConfig(), new ObjectMapper());
        engine.submit(command("seedance"));
        verify(service).generate(anyList(), any(), any(), any(), eq(API_MODEL));
    }

    @Test
    void singleModelMode_submit_unknownModelRejected() {
        SeedanceEngine engine = new SeedanceEngine(mock(SeedanceService.class), singleConfig(), new ObjectMapper());
        assertThrows(RuntimeException.class, () -> engine.submit(command("不存在的模型")));
    }

    @Test
    void multiModelMode_defaultResolution_usesFirstModel_whenBlank() {
        SeedanceEngine engine = multiModelEngine();
        assertEquals("seedance", engine.effectiveModel(null));
        assertEquals("seedance", engine.effectiveModel(""));
    }

    @Test
    void multiModelMode_defaultResolution_prefersExplicitTrimmedModel() {
        SeedanceEngine engine = multiModelEngine();
        assertEquals("seedance-fast", engine.effectiveModel(" seedance-fast "));
    }

    @Test
    void multiModelMode_submit_mapsIdToApiModelName() throws Exception {
        SeedanceService service = mock(SeedanceService.class);
        SeedanceEngine engine = new SeedanceEngine(service, multiConfig(), new ObjectMapper());
        engine.submit(command("seedance-fast"));
        verify(service).generate(anyList(), any(), any(), any(), eq(API_MODEL_FAST));
    }

    @Test
    void multiModelMode_submit_unknownModelRejected() {
        SeedanceEngine engine = new SeedanceEngine(mock(SeedanceService.class), multiConfig(), new ObjectMapper());
        assertThrows(RuntimeException.class, () -> engine.submit(command("nope")));
    }

    @Test
    void userBillingDefaultsToSuccessWithoutEnablingTimeoutRetry() {
        VideoEngine engine = stubEngine();
        assertEquals(BillingTiming.ON_SUCCESS, engine.billingTiming());
        org.junit.jupiter.api.Assertions.assertFalse(engine.timeoutRetrySupported());
    }

    @Test
    void defaultResolution_prefersExplicitTrimmedModel() {
        VideoEngine engine = stubEngine();
        assertEquals("minimax-h3", engine.effectiveModel(" minimax-h3 "));
        assertEquals("seedance", engine.effectiveModel("seedance"));
    }

    @Test
    void defaultResolution_blankFallsBackToFirstModel() {
        VideoEngine engine = stubEngine();
        assertEquals("seedance", engine.effectiveModel(null));
        assertEquals("seedance", engine.effectiveModel(""));
    }

    private SeedanceEngine singleModelEngine() {
        return new SeedanceEngine(mock(SeedanceService.class), singleConfig(), new ObjectMapper());
    }

    private SeedanceEngine multiModelEngine() {
        return new SeedanceEngine(mock(SeedanceService.class), multiConfig(), new ObjectMapper());
    }

    private SeedanceConfig singleConfig() {
        SeedanceConfig config = new SeedanceConfig();
        config.setModel(API_MODEL);
        return config;
    }

    private SeedanceConfig multiConfig() {
        SeedanceConfig config = singleConfig();
        config.setModels(List.of(
                model("seedance", API_MODEL, "Seedance 2.0"),
                model("seedance-fast", API_MODEL_FAST, "Seedance 2.0 Fast")));
        return config;
    }

    private SeedanceConfig.SeedanceModel model(String id, String name, String label) {
        SeedanceConfig.SeedanceModel m = new SeedanceConfig.SeedanceModel();
        m.setId(id);
        m.setName(name);
        m.setLabel(label);
        return m;
    }

    private GenerateCommand command(String model) {
        return GenerateCommand.builder().model(model).build();
    }

    /** 两个模型的最小引擎：第一个作为默认。 */
    private VideoEngine stubEngine() {
        return new VideoEngine() {
            @Override
            public String provider() {
                return "stub";
            }

            @Override
            public SubmitResult submit(GenerateCommand command) {
                return null;
            }

            @Override
            public RemoteStatus poll(VideoTask task) {
                return null;
            }

            @Override
            public List<ModelSpec> models() {
                return List.of(
                        new ModelSpec("stub", "seedance", "Seedance 默认", false, 0, 9,
                                List.of("16:9"), 5, 15, List.of(5, 8, 10)),
                        new ModelSpec("stub", "minimax-h3", "MiniMax-H3", true, 1, 9,
                                List.of("16:9"), 5, 15, List.of())
                );
            }
        };
    }
}
