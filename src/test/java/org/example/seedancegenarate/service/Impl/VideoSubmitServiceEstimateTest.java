package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 估价口径守卫：estimate 必须与 submit 走同一 resolveSpec（提供方默认值 / 生效模型 /
 * 开放闸门 / 时长默认），金额直接透传 PricingService——「按钮显示价 == 提交冻结价」。
 */
class VideoSubmitServiceEstimateTest {

    private VideoEngineRegistry registry;
    private VideoEngine engine;
    private ModelAccessService modelAccessService;
    private PricingService pricingService;
    private VideoTaskService videoTaskService;
    private org.example.seedancegenarate.service.GenerationAttemptService attemptService;
    private org.example.seedancegenarate.service.AsyncJobService asyncJobService;
    private TransactionTemplate transactionTemplate;
    private VideoSubmitServiceImpl service;

    @BeforeEach
    void setUp() {
        registry = mock(VideoEngineRegistry.class);
        engine = mock(VideoEngine.class);
        modelAccessService = mock(ModelAccessService.class);
        pricingService = mock(PricingService.class);
        videoTaskService = mock(VideoTaskService.class);
        attemptService = mock(org.example.seedancegenarate.service.GenerationAttemptService.class);
        asyncJobService = mock(org.example.seedancegenarate.service.AsyncJobService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new VideoSubmitServiceImpl(
                registry,
                videoTaskService,
                modelAccessService,
                mock(TaskStatusTransitioner.class),
                mock(WalletService.class),
                pricingService,
                new ObjectMapper(),
                mock(ApplicationEventPublisher.class),
                new VideoCompletionProperties(),
                mock(org.example.seedancegenarate.mapper.AppUserMapper.class),
                mock(org.example.seedancegenarate.mapper.ApiKeyMapper.class),
                mock(org.example.seedancegenarate.service.ConcurrencyPolicy.class),
                mock(org.example.seedancegenarate.service.AdmissionControl.class),
                attemptService,
                asyncJobService,
                transactionTemplate);
        ReflectionTestUtils.setField(service, "defaultProvider", "seedance");
        when(registry.get("seedance")).thenReturn(engine);
        when(engine.effectiveModel(any())).thenReturn("seedance-v1-pro");
        when(engine.outputType("seedance-v1-pro")).thenReturn(OutputType.VIDEO);
        var config = new org.example.seedancegenarate.config.SeedanceConfig();
        var model = new org.example.seedancegenarate.config.SeedanceConfig.SeedanceModel();
        model.setId("seedance-v1-pro");
        model.setName("fixture-provider-model");
        config.setModels(java.util.List.of(model));
        when(engine.models()).thenReturn(new org.example.seedancegenarate.engine.Impl.SeedanceEngine(
                mock(org.example.seedancegenarate.service.SeedanceService.class), config, new ObjectMapper()).models());
        when(modelAccessService.isOpen("seedance-v1-pro")).thenReturn(true);
        when(pricingService.price(any())).thenReturn(
                new PricingService.Price(new BigDecimal("0.20"), new BigDecimal("1.60"), "CNY"));
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // 【测什么】2K报价与提交落库、计价探针同为0.9MP，金额仍透传旧费率。
    // 【怎么算红】仅报价解析、提交丢resolution，或用MP乘金额时本测试失败。
    @Test void resolutionQuoteAndTaskUseSameMpAndExistingPrice() throws Exception {
        var spec = new org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3T2vHdWorkflowBuilder(new ObjectMapper()).spec();
        when(registry.get("comfyui")).thenReturn(engine);
        when(engine.effectiveModel(spec.model())).thenReturn(spec.model());
        when(engine.models()).thenReturn(java.util.List.of(spec));
        when(modelAccessService.isOpen(spec.model())).thenReturn(true);
        org.mockito.Mockito.doAnswer(i -> { ((VideoTask)i.getArgument(0)).setId(73L); return true; })
                .when(videoTaskService).save(any(VideoTask.class));
        var attempt = new GenerationAttempt(); attempt.setId(83L);
        when(attemptService.stageCurrentAttempt(any(),org.mockito.ArgumentMatchers.eq(1),any())).thenReturn(attempt);
        var quote = service.estimate("comfyui",spec.model(),8,"2k",null);
        var task = service.submit(new VideoSubmitService.SubmitRequest(null,"comfyui",spec.model(),"prompt",
                java.util.List.of(),java.util.List.of(),java.util.List.of(),8,"16:9",null,null,"tier-new",null,
                java.util.List.of(),"2k"));
        assertEquals("2k",quote.resolution()); assertEquals(0.9,quote.megapixels());
        assertEquals(quote.megapixels(),task.getMegapixels());
        assertEquals(new BigDecimal("1.60"),quote.amount()); assertEquals(quote.amount(),task.getFreezeAmount());
        var capture=ArgumentCaptor.forClass(VideoTask.class);
        verify(pricingService,org.mockito.Mockito.times(2)).price(capture.capture());
        assertTrue(capture.getAllValues().stream().allMatch(t -> Double.valueOf(0.9).equals(t.getMegapixels())));
        verify(engine,never()).submit(any());
    }

    // 【测什么】新请求非法档位在计价、任务落库和作业副作用之前400。
    // 【怎么算红】移除estimate或submit的resolution校验会访问pricing或返回非400。
    @Test void unsupportedTierFailsBeforePricingOrPersistence() {
        assertEquals(400,assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                () -> service.estimate("seedance","seedance-v1-pro",8,"4k",null)).getCode());
        assertEquals(400,assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                () -> service.submit(new VideoSubmitService.SubmitRequest(null,"seedance","seedance-v1-pro","p",
                        java.util.List.of(),java.util.List.of(),java.util.List.of(),8,"16:9",null,null,"invalid",null,
                        java.util.List.of(),"4k"))).getCode());
        verify(pricingService,never()).price(any()); verify(videoTaskService,never()).save(any(VideoTask.class));
        org.mockito.Mockito.verifyNoInteractions(asyncJobService);
    }

    @Test
    void defaultsMatchSubmitSemantics() {
        // 测什么：不传 provider/model/duration → 默认提供方 + effectiveModel + duration 默认 8，
        //        计价探针字段与 submit 落库口径逐一一致，金额透传 PricingService
        // 怎么算红：resolveSpec 口径漂移（默认值改动 / 未走 effectiveModel / outputType 缺失）
        //        —— 按钮显示价与提交冻结价分家
        VideoSubmitService.PriceEstimate estimate = service.estimate(null, null, null);

        ArgumentCaptor<VideoTask> probe = ArgumentCaptor.forClass(VideoTask.class);
        verify(pricingService).price(probe.capture());
        assertEquals("seedance", probe.getValue().getProvider());
        assertEquals("seedance-v1-pro", probe.getValue().getModel());
        assertEquals(8, probe.getValue().getDuration());
        assertEquals("VIDEO", probe.getValue().getOutputType());
        assertEquals(new BigDecimal("1.60"), estimate.amount());
        assertEquals(new BigDecimal("0.20"), estimate.unitPrice());
        assertEquals("CNY", estimate.currency());
        assertEquals(8, estimate.duration());
    }

    @Test
    void explicitDurationPassesThrough() {
        // 测什么：显式 duration=5 原样进入计价探针（不被默认值 8 覆盖）
        // 怎么算红：估价忽略用户当前选择的时长，切时长按钮金额不变——显示价与冻结价按不同时长算
        service.estimate("seedance", "seedance-v1-pro", 5);

        ArgumentCaptor<VideoTask> probe = ArgumentCaptor.forClass(VideoTask.class);
        verify(pricingService).price(probe.capture());
        assertEquals(5, probe.getValue().getDuration());
    }

    // 【测什么】非法离散时长在估价和提交都被拒绝，没有报价或任务副作用。
    // 【怎么算红】任何一个入口漏接共享时长校验，本测试不再收到400。
    @Test void quoteAndSubmitRejectTheSameDurationHole() {
        for (int duration : new int[]{0, 1, 6, 16}) {
            assertEquals(400, assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                    () -> service.estimate("seedance", "seedance-v1-pro", duration)).getCode());
            assertEquals(400, assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                    () -> service.submit(new VideoSubmitService.SubmitRequest(null, "seedance", "seedance-v1-pro",
                            "prompt", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                            duration, "16:9", null, null, "invalid-duration", null))).getCode());
        }
        verify(pricingService, never()).price(any());
        verify(videoTaskService, never()).save(any(VideoTask.class));
    }

    // 【测什么】真实IMAGE模型接收UI的1占位，报价探针和提交落库均为8，金额同源。
    // 【怎么算红】拒绝IMAGE的1或仅报价归一而任务仍存1，异常或字段断言使本测试失败。
    @Test void imageOneSecondPlaceholderQuotesAndPersistsAsEight() throws Exception {
        var spec = new org.example.seedancegenarate.engine.comfyui.Impl.ZImageTurboWorkflowBuilder(new ObjectMapper()).spec();
        when(registry.get("comfyui")).thenReturn(engine);
        when(engine.effectiveModel(spec.model())).thenReturn(spec.model());
        when(engine.models()).thenReturn(java.util.List.of(spec));
        when(modelAccessService.isOpen(spec.model())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0); task.setId(72L); return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        var attempt = new GenerationAttempt(); attempt.setId(82L);
        when(attemptService.stageCurrentAttempt(any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(attempt);

        var estimate = service.estimate("comfyui", spec.model(), 1);
        var task = service.submit(new VideoSubmitService.SubmitRequest(null, "comfyui", spec.model(),
                "image prompt", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                1, "16:9", null, null, "image-one-placeholder", null));

        assertEquals(8, estimate.duration());
        assertEquals("IMAGE", estimate.outputType());
        assertEquals(8, task.getDuration());
        assertEquals("IMAGE", task.getOutputType());
        assertEquals(estimate.amount(), task.getFreezeAmount());
        ArgumentCaptor<VideoTask> priced = ArgumentCaptor.forClass(VideoTask.class);
        verify(pricingService, org.mockito.Mockito.times(2)).price(priced.capture());
        for (VideoTask value : priced.getAllValues()) {
            assertEquals(8, value.getDuration());
            assertEquals("IMAGE", value.getOutputType());
        }
        verify(videoTaskService).save(task);
        verify(engine, never()).submit(any());
    }

    // 【测什么】音频缺省与非API旧16:9占位均落null比例，报价同源且内部长提示词保留。
    // 【怎么算红】删除历史占位兼容、落库补比例或内部增加5000限制，本测试失败。
    @Test void audioDefaultMatchesPersistedTask() throws Exception {
        var spec = new org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxMusic3WorkflowBuilder(new ObjectMapper()).spec();
        when(registry.get("comfyui")).thenReturn(engine);
        when(engine.effectiveModel(spec.model())).thenReturn(spec.model());
        when(engine.models()).thenReturn(java.util.List.of(spec));
        when(modelAccessService.isOpen(spec.model())).thenReturn(true);
        var estimate = service.estimate("comfyui", spec.model(), null);
        org.mockito.Mockito.doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0); task.setId(71L); return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        var attempt = new GenerationAttempt(); attempt.setId(81L);
        when(attemptService.stageCurrentAttempt(any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(attempt);
        String prompt = "音".repeat(6000);
        for (String ratio : new String[]{null, "16:9"}) {
            var task = service.submit(new VideoSubmitService.SubmitRequest(null, "comfyui", spec.model(),
                    prompt, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    null, ratio, null, null, "audio-default-" + ratio, null));
            assertEquals(spec.durationMin(), estimate.duration());
            assertEquals(estimate.duration(), task.getDuration());
            assertEquals("AUDIO", task.getOutputType());
            assertEquals(prompt, task.getPrompt());
            org.junit.jupiter.api.Assertions.assertNull(task.getRatio());
            assertEquals(estimate.amount(), task.getFreezeAmount());
        }
    }

    // 【测什么】API音频的16:9和非API的其他显式比例仍在定价/落库前400。
    // 【怎么算红】删除apiKeyId条件或放宽16:9精确匹配，对应请求不再400。
    @Test void audioPlaceholderCompatibilityNeverAppliesToApiOrOtherRatios() {
        var spec = new org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxMusic3WorkflowBuilder(new ObjectMapper()).spec();
        when(registry.get("comfyui")).thenReturn(engine);
        when(engine.effectiveModel(spec.model())).thenReturn(spec.model());
        when(engine.models()).thenReturn(java.util.List.of(spec));
        when(modelAccessService.isOpen(spec.model())).thenReturn(true);
        for (String ratio : java.util.List.of("16:9", "9:16", "16:9 ")) {
            Long apiKeyId = "16:9".equals(ratio) ? 12L : null;
            assertEquals(400, assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                    () -> service.submit(new VideoSubmitService.SubmitRequest(null, "comfyui", spec.model(),
                            "prompt", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                            30, ratio, null, apiKeyId, "audio-ratio", null))).getCode());
        }
        verify(pricingService, never()).price(any());
        verify(videoTaskService, never()).save(any(VideoTask.class));
        verify(attemptService, never()).stageCurrentAttempt(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // 【测什么】兼容不覆盖非音频空比例能力，也不吞掉有比例能力音频的非法16:9。
    // 【怎么算红】删除AUDIO或ratios空条件，对应模型的16:9会被静默归一并越过400。
    @Test void audioPlaceholderCompatibilityRequiresAudioAndEmptyRatios() {
        for (var spec : java.util.List.of(
                new org.example.seedancegenarate.engine.ModelSpec("seedance", "seedance-v1-pro", "Video",
                        false, 0, 0, java.util.List.of(), 5, 15, java.util.List.of(5, 8), OutputType.VIDEO),
                new org.example.seedancegenarate.engine.ModelSpec("seedance", "seedance-v1-pro", "Audio",
                        false, 0, 0, java.util.List.of("1:1"), 30, 300, java.util.List.of(30), OutputType.AUDIO))) {
            when(engine.models()).thenReturn(java.util.List.of(spec));
            assertEquals(400, assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                    () -> service.submit(new VideoSubmitService.SubmitRequest(null, "seedance", spec.model(),
                            "prompt", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                            null, "16:9", null, null, "ratio-capability", null))).getCode());
        }
        verify(pricingService, never()).price(any());
        verify(videoTaskService, never()).save(any(VideoTask.class));
    }

    // 【测什么】开放闸门通过但没有ModelSpec时拒绝，不以mock或历史默认绕过能力校验。
    // 【怎么算红】找不到能力时回退默认VIDEO/8，本测试将进入定价而不是400。
    @Test void missingCapabilitiesDoNotFallBackToDefaults() {
        when(engine.models()).thenReturn(java.util.List.of());
        assertEquals(400, assertThrows(org.example.seedancegenarate.exception.BusinessException.class,
                () -> service.estimate("seedance", "seedance-v1-pro", 8)).getCode());
        verify(pricingService, never()).price(any());
    }

    @Test
    void closedModelRejectedBySameGate() {
        // 测什么：未开放模型（非管理员）→ 估价与 submit 同一 assertModelOpen 闸门拒绝，且不触发计价
        // 怎么算红：估价绕过开放闸门——未开放模型的定价可被普通用户探测
        when(modelAccessService.isOpen("seedance-v1-pro")).thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.estimate(null, null, null));
        assertTrue(ex.getMessage().contains("未开放"));
        verify(pricingService, never()).price(any());
    }

    @Test
    void ordinaryUsersCannotPinAComfyNodeBeforeAnySubmitSideEffect() {
        // 【测什么】普通用户即使伪造 nodeId，也在解析模型、落任务、冻结资金之前被 403 拒绝
        // 【怎么算红】只在前端隐藏“指定节点”入口、服务层不守 —— 用户直接改请求体即可绕过
        //          enabled / healthy / capability / vram 全部过滤，把真实任务强塞给维护中的机器
        AppUser user = new AppUser();
        user.setId(7L);
        user.setRole("USER");
        UserContext.setUser(user);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.submit(
                new VideoSubmitService.SubmitRequest(7L, "comfyui", "t2v", "test",
                        java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        5, "16:9", null, null, "ui:pin-denied", "gpu-disabled")));

        assertTrue(error.getMessage().contains("管理员"));
        verify(registry, never()).get(any());
        verify(pricingService, never()).price(any());
    }

    @Test
    void administratorPinnedNodeReachesTheDurableAttempt() throws Exception {
        // 【测什么】管理员 nodeId 进入持久化 attempt，后续 Worker 才据此指定节点。
        // 【怎么算红】DTO/API 虽然收了 nodeId，但 stage 时漏传 ——
        //          页面显示“正在验证 gpu-new”，实际仍走普通负载均衡，验证结论完全错误
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setRole("ADMIN");
        UserContext.setUser(admin);
        when(registry.get("comfyui")).thenReturn(engine);
        var spec = new org.example.seedancegenarate.engine.comfyui.Impl.MiniMaxH3TextToVideoWorkflowBuilder(new ObjectMapper()).spec();
        when(engine.effectiveModel("t2v")).thenReturn(spec.model());
        when(engine.models()).thenReturn(java.util.List.of(spec));
        when(modelAccessService.isOpen(spec.model())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0);
            task.setId(71L);
            return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        when(attemptService.stageCurrentAttempt(any(VideoTask.class),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("gpu-new")))
                .thenAnswer(invocation -> {
                    VideoTask task = invocation.getArgument(0);
                    GenerationAttempt attempt = new GenerationAttempt();
                    attempt.setId(81L);
                    task.setCurrentAttemptId(81L);
                    task.setPhase("QUEUED");
                    return attempt;
                });

        service.submit(new VideoSubmitService.SubmitRequest(null, "comfyui", "t2v", "test",
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                5, "16:9", null, null, "ui:pin-admin", "gpu-new"));

        verify(attemptService).stageCurrentAttempt(any(VideoTask.class),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("gpu-new"));
        verify(asyncJobService).enqueue(
                org.mockito.ArgumentMatchers.eq(org.example.seedancegenarate.service.GenerationAttemptService.JOB_TYPE),
                org.mockito.ArgumentMatchers.eq("attempt:81"),
                org.mockito.ArgumentMatchers.eq("{\"attemptId\":81}"));
        verify(engine, never()).submit(any());
    }
}
