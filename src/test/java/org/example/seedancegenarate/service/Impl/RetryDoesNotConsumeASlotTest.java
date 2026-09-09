package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.service.ModelAccessService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时自动重试不能占第二个并发槽位，也不能被限额挡住。
 * <p>
 * 这条正确性来自结构 —— {@code resubmit()} 是和 {@code submit()} 平行的另一个方法，
 * 任务全程不离开 PROCESSING，所以它握着的还是首投那一个槽位。
 * <p>
 * 但结构性正确最容易在后来的重构里丢掉（比如有人为了"复用"把 resubmit 改成走 submit），
 * 而且丢掉之后<b>症状极难归因</b>：企业跑满时，所有需要重试的任务会被自己的限额挡住、
 * 一路重试到耗尽、判超时失败 —— 看起来像引擎不稳定，没人会想到是限额干的。
 */
class RetryDoesNotConsumeASlotTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // resubmit 的 CAS 回写用的是 lambdaUpdate，纯单测里要先喂一份实体元数据
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                VideoTask.class);
    }

    private VideoEngineRegistry registry;
    private VideoEngine engine;
    private VideoTaskService videoTaskService;
    private AdmissionControl admissionControl;
    private ConcurrencyPolicy concurrencyPolicy;
    private AppUserMapper appUserMapper;
    private org.example.seedancegenarate.mapper.ApiKeyMapper apiKeyMapper;
    private VideoSubmitServiceImpl service;
    private GenerationAttemptService attemptService;
    private AsyncJobService asyncJobService;

    @BeforeEach
    void setUp() {
        registry = mock(VideoEngineRegistry.class);
        engine = mock(VideoEngine.class);
        videoTaskService = mock(VideoTaskService.class);
        admissionControl = mock(AdmissionControl.class);
        concurrencyPolicy = mock(ConcurrencyPolicy.class);
        appUserMapper = mock(AppUserMapper.class);
        apiKeyMapper = mock(org.example.seedancegenarate.mapper.ApiKeyMapper.class);
        ModelAccessService modelAccessService = mock(ModelAccessService.class);
        attemptService = mock(GenerationAttemptService.class);
        asyncJobService = mock(AsyncJobService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new VideoSubmitServiceImpl(
                registry, videoTaskService, modelAccessService,
                mock(TaskStatusTransitioner.class), mock(WalletService.class),
                mock(PricingService.class), new ObjectMapper(),
                mock(ApplicationEventPublisher.class), new VideoCompletionProperties(),
                appUserMapper, apiKeyMapper, concurrencyPolicy, admissionControl,
                attemptService, asyncJobService, transactionTemplate);
        ReflectionTestUtils.setField(service, "defaultProvider", "comfyui");

        when(registry.get("comfyui")).thenReturn(engine);
        when(engine.outputType(any())).thenReturn(OutputType.VIDEO);
        when(modelAccessService.isOpen(any())).thenReturn(true);
        when(videoTaskService.update(any())).thenReturn(true);
        when(attemptService.stageCurrentAttempt(any(), any(Integer.class), any()))
                .thenAnswer(invocation -> {
                    VideoTask task = invocation.getArgument(0);
                    GenerationAttempt attempt = new GenerationAttempt();
                    attempt.setId(88L);
                    task.setCurrentAttemptId(88L);
                    task.setPhase("QUEUED");
                    return attempt;
                });
    }

    private VideoTask processingTask() {
        VideoTask t = new VideoTask();
        t.setId(42L);
        t.setUserId(7L);
        t.setBizTaskId("tsk_retry");
        t.setStatus("PROCESSING");
        t.setProvider("comfyui");
        t.setModel("wan2.2");
        t.setDuration(5);
        t.setRatio("16:9");
        t.setRetryCount(0);
        t.setCurrentAttemptId(41L);
        t.setProviderTaskId("old-provider-task");
        t.setNodeId("old-node");
        t.setPhase("RUNNING");
        return t;
    }

    @Test
    void resubmitNeverAsksForAnotherSlot() throws Exception {
        // 【测什么】重投走的路径完全不碰并发额度：不占位、不解析上限、不读 app_user
        // 【怎么算红】把 resubmit 改成复用 submit（或在里面加一次 acquire）——
        //          任务从没离开 PROCESSING，它握着的还是首投那个槽位，再占一次就是重复计数；
        //          更糟的是企业跑满时重投会被自己的限额拒掉，一路重试到耗尽判超时失败，
        //          现场看起来像引擎不稳定，不会有人想到是限额干的
        boolean done = service.resubmit(processingTask());

        assertTrue(done, "重投本身要成功");
        verifyNoInteractions(admissionControl);
        verifyNoInteractions(concurrencyPolicy);
        verifyNoInteractions(appUserMapper);
        verifyNoInteractions(apiKeyMapper);
        verifyNoInteractions(engine);
        verify(attemptService).stageCurrentAttempt(any(VideoTask.class), org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void resubmitAtomicallyDetachesOldRemoteTaskBeforeStagingAttempt() throws Exception {
        // 【测什么】重投事务先 CAS 清空旧 provider/node，再创建新 attempt/job，旧回调失去当前入口。
        // 【怎么算红】保留旧 provider_task_id 时，新 submit HTTP 期间迟到回调仍可抢先终态。

        service.resubmit(processingTask());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VideoTask>>
                claim = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(videoTaskService).update(claim.capture());
        String sqlSet = claim.getValue().getSqlSet();
        assertTrue(sqlSet.contains("provider_task_id"), sqlSet);
        assertTrue(sqlSet.contains("node_id"), sqlSet);
        verify(asyncJobService).enqueue(org.mockito.ArgumentMatchers.eq(GenerationAttemptService.JOB_TYPE),
                org.mockito.ArgumentMatchers.eq("attempt:88"), any(String.class));
        verifyNoInteractions(engine);
    }
}
