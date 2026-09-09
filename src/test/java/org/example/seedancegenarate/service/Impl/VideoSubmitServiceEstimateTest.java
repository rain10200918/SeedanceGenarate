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
        when(modelAccessService.isOpen("seedance-v1-pro")).thenReturn(true);
        when(pricingService.price(any())).thenReturn(
                new PricingService.Price(new BigDecimal("0.20"), new BigDecimal("1.60"), "CNY"));
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
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
        when(engine.effectiveModel("t2v")).thenReturn("t2v");
        when(engine.outputType("t2v")).thenReturn(OutputType.VIDEO);
        when(modelAccessService.isOpen("t2v")).thenReturn(true);
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
