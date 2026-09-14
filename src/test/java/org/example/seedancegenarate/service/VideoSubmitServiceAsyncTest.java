package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.Impl.VideoSubmitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class VideoSubmitServiceAsyncTest {

    private final VideoEngineRegistry engineRegistry = mock(VideoEngineRegistry.class);
    private final VideoEngine engine = mock(VideoEngine.class);
    private final VideoTaskService videoTaskService = mock(VideoTaskService.class);
    private final ModelAccessService modelAccessService = mock(ModelAccessService.class);
    private final TaskStatusTransitioner transitioner = mock(TaskStatusTransitioner.class);
    private final WalletService walletService = mock(WalletService.class);
    private final PricingService pricingService = mock(PricingService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppUserMapper appUserMapper = mock(AppUserMapper.class);
    private final ApiKeyMapper apiKeyMapper = mock(ApiKeyMapper.class);
    private final ConcurrencyPolicy concurrencyPolicy = mock(ConcurrencyPolicy.class);
    private final AdmissionControl admissionControl = mock(AdmissionControl.class);
    private final GenerationAttemptService attemptService = mock(GenerationAttemptService.class);
    private final AsyncJobService asyncJobService = mock(AsyncJobService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private VideoSubmitServiceImpl service;

    @BeforeEach
    void setUp() {
        ConcurrencyLimit limit = mock(ConcurrencyLimit.class);
        when(limit.unlimited()).thenReturn(true);
        when(concurrencyPolicy.resolve(any(), any())).thenReturn(limit);
        when(engineRegistry.get("seedance")).thenReturn(engine);
        when(engine.effectiveModel(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.outputType(anyString())).thenReturn(OutputType.VIDEO);
        var config = new org.example.seedancegenarate.config.SeedanceConfig();
        var model = new org.example.seedancegenarate.config.SeedanceConfig.SeedanceModel();
        model.setId("seedance-1-0-pro-250528");
        model.setName("fixture-provider-model");
        config.setModels(java.util.List.of(model));
        when(engine.models()).thenReturn(new org.example.seedancegenarate.engine.Impl.SeedanceEngine(
                mock(SeedanceService.class), config, objectMapper).models());
        when(modelAccessService.isOpen(anyString())).thenReturn(true);
        when(pricingService.price(any(VideoTask.class)))
                .thenReturn(new PricingService.Price(BigDecimal.ONE, BigDecimal.valueOf(100), "CNY"));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new VideoSubmitServiceImpl(engineRegistry, videoTaskService, modelAccessService,
                transitioner, walletService, pricingService, objectMapper, mock(ApplicationEventPublisher.class),
                mock(VideoCompletionProperties.class), appUserMapper, apiKeyMapper, concurrencyPolicy,
                admissionControl, attemptService, asyncJobService, transactionTemplate);
    }

    @Test
    void submitStagesAttemptAndJobWithoutCallingProvider() throws Exception {
        // 【测什么】首次提交只落任务、冻结与异步提交作业，并立即返回稳定 taskId。
        // 【怎么算红】旧实现会直接调用 provider，且不会创建 attempt/GENERATION_SUBMIT job。
        doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0);
            task.setId(91L);
            return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(701L);
        attempt.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptService.stageCurrentAttempt(any(VideoTask.class), eq(1), eq(null)))
                .thenAnswer(invocation -> {
                    VideoTask task = invocation.getArgument(0);
                    task.setCurrentAttemptId(701L);
                    task.setPhase("QUEUED");
                    return attempt;
                });

        VideoTask result = service.submit(request("same-request"));

        assertThat(result.getId()).isEqualTo(91L);
        assertThat(result.getCurrentAttemptId()).isEqualTo(701L);
        assertThat(result.getPhase()).isEqualTo("QUEUED");
        verify(walletService).freeze(8L, BigDecimal.valueOf(100), 91L);
        verify(attemptService).stageCurrentAttempt(result, 1, null);
        verify(asyncJobService).enqueue(eq(GenerationAttemptService.JOB_TYPE),
                eq(GenerationAttemptService.jobKey(701L)), anyString());
        verify(engine, never()).submit(any());
    }

    // 【测什么】内部稳定图片引用随原Task落库，冻结与Job仍沿旧提交链；不提前签名。
    // 【怎么算红】删掉setStoredImageReferences时持久字段断言失败。
    @Test void referenceSubmissionPersistsIdentityWithoutTemporaryUrl() throws Exception {
        var resolver=mock(StoredImageReferences.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"storedReferences",resolver);
        doAnswer(i->{VideoTask t=i.getArgument(0);t.setId(91L);return true;}).when(videoTaskService).save(any(VideoTask.class));
        var attempt=new GenerationAttempt();attempt.setId(701L);attempt.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptService.stageCurrentAttempt(any(),eq(1),eq(null))).thenReturn(attempt);
        var old=request("reference-request");var ref=new StoredImageReferences.Reference("source","outputs/cat.png");
        var input=new VideoSubmitService.SubmitRequest(old.userId(),old.provider(),old.model(),old.prompt(),java.util.List.of(),old.videoUrls(),old.audioUrls(),old.duration(),old.ratio(),old.megapixels(),old.apiKeyId(),old.requestId(),old.nodeId(),java.util.List.of(ref));
        var result=service.submit(input);
        assertThat(result.getStoredImageReferences()).contains("outputs/cat.png","source").doesNotContain("https://");
        assertThat(result.getImages()).isNull();verify(resolver).validate(old.userId(),ref);
        verify(resolver,never()).signedUrls(any(),any());verify(engine,never()).submit(any());
        verify(walletService).freeze(8L,BigDecimal.valueOf(100),91L);
    }

    @Test
    void duplicateRequestReturnsConcurrentWinnerWithoutSecondSideEffects() throws Exception {
        // 【测什么】两个实例并发插入相同 requestId 时，输家读取并返回数据库赢家。
        // 【怎么算红】若仅依赖提交前查询，唯一键异常会直接冒泡或重复冻结/占槽。
        VideoTask winner = new VideoTask();
        winner.setId(99L);
        winner.setStatus("SUCCESS");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(null, winner);
        when(videoTaskService.save(any(VideoTask.class))).thenThrow(new DuplicateKeyException("uk request"));

        VideoTask result = service.submit(request("same-request"));

        assertThat(result).isSameAs(winner);
        verify(admissionControl, never()).acquire(anyLong(), anyLong(), any(), any());
        verify(walletService, never()).freeze(anyLong(), any(BigDecimal.class), anyLong());
        verify(transactionTemplate, never()).execute(any());
    }

    // 【测什么】Agent确认报价以后到实际冻结前再涨价，必须在任务落库与钱包前拒绝。
    // 【怎么算红】删除submitInternal的approved金额比较，将进入save路径而非409，且验证save never失败。
    @Test void approvedQuoteIsCheckedAtTheActualFreezePriceBoundary() {
        var approved=new VideoSubmitService.PriceEstimate("seedance","seedance-1-0-pro-250528",5,"VIDEO",BigDecimal.ONE,BigDecimal.TEN,"CNY");
        assertThatThrownBy(()->service.submitApproved(request("agent-approval:1"),approved)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);
        verify(videoTaskService,never()).save(any(VideoTask.class));
        verify(walletService,never()).freeze(anyLong(),any(BigDecimal.class),anyLong());
        verify(asyncJobService,never()).enqueue(anyString(),anyString(),anyString());
    }

    // 【测什么】相同金额但不同币种或模型的确认，不能被当成可用支付授权。
    // 【怎么算红】去掉币种与模型校验会进入任务save，错误类型不再是409。
    @Test void approvedQuoteBindsCurrencyAndModel() {
        for(var approved:java.util.List.of(
                new VideoSubmitService.PriceEstimate("seedance","wrong-model",5,"VIDEO",null,BigDecimal.valueOf(100),"CNY"),
                new VideoSubmitService.PriceEstimate("seedance","seedance-1-0-pro-250528",5,"VIDEO",null,BigDecimal.valueOf(100),"USD"))) {
            assertThatThrownBy(()->service.submitApproved(request("agent-approval:2"),approved)).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);
        }
        verify(videoTaskService,never()).save(any(VideoTask.class));
    }

    @Test
    void existingHalfBuiltTaskReturnsConflictInsteadOfGhostTaskId() {
        // 【测什么】前置幂等快路只看到 autocommit task 行时，不能把可能被补偿删除的 taskId 返回客户端。
        // 【怎么算红】只判 existing != null 就 return，这条会收到 VideoTask 而不是 409。
        VideoTask halfBuilt = new VideoTask();
        halfBuilt.setId(100L);
        halfBuilt.setStatus("PROCESSING");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(halfBuilt);

        assertThatThrownBy(() -> service.submit(request("same-request")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage()).contains("同一请求正在受理");
                });

        verify(videoTaskService, never()).save(any(VideoTask.class));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void uploadPreflightRejectsHalfBuiltTaskInsteadOfReturningGhostId() {
        // 【测什么】Controller 上传素材前使用的快路也经过 durable gate，半成品统一 409。
        // 【怎么算红】Controller 继续调用 raw findByRequestId 时会返回可能随后被删除的 ghost taskId。
        VideoTask halfBuilt = new VideoTask();
        halfBuilt.setId(102L);
        halfBuilt.setStatus("PROCESSING");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(halfBuilt);

        assertThatThrownBy(() -> service.findAcceptedByRequestId(8L, "same-request"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage()).contains("同一请求正在受理");
                });
    }

    @Test
    void duplicateHalfBuiltWinnerReturnsConflictInsteadOfGhostTaskId() {
        // 【测什么】并发插入输家在 DuplicateKey catch 中读到未完成 attempt/job 的赢家时，同样不能返回 ghost taskId。
        // 【怎么算红】catch 分支仍只判 winner != null 就 return，这条不会抛 409。
        VideoTask halfBuilt = new VideoTask();
        halfBuilt.setId(101L);
        halfBuilt.setStatus("PROCESSING");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(null, halfBuilt);
        when(videoTaskService.save(any(VideoTask.class))).thenThrow(new DuplicateKeyException("uk request"));

        assertThatThrownBy(() -> service.submit(request("same-request")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage()).contains("同一请求正在受理");
                });

        verify(admissionControl, never()).acquire(anyLong(), anyLong(), any(), any());
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void stagingFailureReleasesAdmissionBeforeDeletingTask() throws Exception {
        // 【测什么】freeze/attempt/job 任一失败都先幂等释放 Redis 槽，再删除刚建任务。
        // 【怎么算红】补偿倒序或漏掉 release/remove 会留下占槽或 PROCESSING 僵尸行。
        doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0);
            task.setId(92L);
            return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(702L);
        when(attemptService.stageCurrentAttempt(any(VideoTask.class), eq(1), eq(null)))
                .thenAnswer(invocation -> {
                    VideoTask task = invocation.getArgument(0);
                    task.setCurrentAttemptId(702L);
                    task.setPhase("QUEUED");
                    return attempt;
                });
        org.mockito.Mockito.doThrow(new IllegalStateException("job insert failed"))
                .when(asyncJobService).enqueue(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.submit(request("failed-request")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("job insert failed");

        org.mockito.InOrder cleanup = inOrder(admissionControl, videoTaskService);
        cleanup.verify(admissionControl).releaseQuietly(8L, 92L, null);
        cleanup.verify(videoTaskService).removeById(92L);
        verify(engine, never()).submit(any());
    }

    @Test
    void commitUnknownWithDurableAttemptNeverDeletesAcceptedTask() throws Exception {
        // 【测什么】Tx-B callback 已写 durable attempt/job、commit 响应却未知时，重新读到 durable winner 就按已受理返回。
        // 【怎么算红】任意 TransactionTemplate 异常都直接 release + delete，会删除已提交 task 并留下冻结/attempt/job 孤儿。
        VideoTask durable = new VideoTask();
        durable.setId(93L);
        durable.setBizTaskId("tsk_durable");
        durable.setTaskId("tsk_durable");
        durable.setStatus("PROCESSING");
        durable.setCurrentAttemptId(703L);
        durable.setPhase("QUEUED");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(null, durable);
        doAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0);
            task.setId(93L);
            return true;
        }).when(videoTaskService).save(any(VideoTask.class));
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(703L);
        when(attemptService.stageCurrentAttempt(any(VideoTask.class), eq(1), eq(null)))
                .thenAnswer(invocation -> {
                    VideoTask task = invocation.getArgument(0);
                    task.setCurrentAttemptId(703L);
                    task.setPhase("QUEUED");
                    return attempt;
                });
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            callback.doInTransaction(mock(TransactionStatus.class));
            throw new TransactionSystemException("commit outcome unknown");
        }).when(transactionTemplate).execute(any());

        VideoTask result = service.submit(request("commit-unknown"));

        assertThat(result).isSameAs(durable);
        verify(admissionControl, never()).releaseQuietly(anyLong(), anyLong(), any());
        verify(videoTaskService, never()).removeById(anyLong());
    }

    // 【测什么】已受理重放先于模型/节点/失效参考校验，返回原任务且不重新冻结。
    // 【怎么算红】把模型或引用校验挪回幂等查询之前，会抛异常而不返回原任务。
    @Test void acceptedReplayIgnoresChangedCapabilitiesAndExpiredReferences() throws Exception {
        var winner = new VideoTask(); winner.setId(99L); winner.setStatus("SUCCESS");
        when(videoTaskService.getOne(any(), eq(false))).thenReturn(winner);
        when(modelAccessService.isOpen(anyString())).thenReturn(false);
        when(engine.models()).thenReturn(java.util.List.of());
        var resolver = mock(StoredImageReferences.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "storedReferences", resolver);
        var input = new VideoSubmitService.SubmitRequest(8L, "seedance", "removed", "old prompt",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), -1, "unsupported", Double.NaN,
                null, "same-request", "old-pinned-node",
                java.util.List.of(new StoredImageReferences.Reference("old-task", "expired.png")));
        for (String status : java.util.List.of("SUCCESS", "FAILED", "PROCESSING")) {
            winner.setStatus(status);
            winner.setCurrentAttemptId(701L);
            assertThat(service.submit(input)).isSameAs(winner);
        }
        org.mockito.Mockito.verifyNoInteractions(engineRegistry, modelAccessService, resolver,
                pricingService, walletService, admissionControl, attemptService, asyncJobService);
        verify(videoTaskService, never()).save(any(VideoTask.class));
    }

    // 【测什么】全量参数在落库/定价/冻结/Job之前校验，内部参考也占图片额度。
    // 【怎么算红】去掉提交的validate调用或不计stored引用，非法请求会越过400边界。
    @Test void invalidParametersHaveNoSubmissionSideEffects() {
        var base = request("invalid");
        var images = java.util.Collections.nCopies(10, "https://example.test/image.png");
        var invalid = java.util.List.of(
                new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), 5, "2:1", null, null, "ratio", null),
                new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), 5, "16:9", 1.0, null, "resolution", null),
                new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                        images, java.util.List.of(), java.util.List.of(), 5, "16:9", null, null, "images", null),
                new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                        java.util.List.of(), java.util.List.of("video"), java.util.List.of(), 5, "16:9", null, null, "video", null),
                new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of("audio"), 5, "16:9", null, null, "audio", null));
        for (var input : invalid)
            assertThatThrownBy(() -> service.submit(input)).isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(400);
        var textOnly = new org.example.seedancegenarate.engine.ModelSpec("seedance", base.model(), "Text only",
                false, 0, 0, java.util.List.of("16:9"), 5, 15, java.util.List.of(5, 8, 10, 15));
        when(engine.models()).thenReturn(java.util.List.of(textOnly));
        var resolver = mock(StoredImageReferences.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "storedReferences", resolver);
        var stored = new VideoSubmitService.SubmitRequest(8L, base.provider(), base.model(), base.prompt(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), 5, "16:9", null, null, "stored", null,
                java.util.List.of(new StoredImageReferences.Reference("source", "image.png")));
        assertThatThrownBy(() -> service.submit(stored)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(resolver, pricingService, walletService, admissionControl,
                attemptService, asyncJobService);
        verify(videoTaskService, never()).save(any(VideoTask.class));
    }

    // 【测什么】API提交把预算与钱包协调放在attempt/job之前，重放仍不重复授权。
    // 【怎么算红】把API分支改回直接wallet.freeze会漏调billingAuthorization并使本测试失败。
    @Test void apiSubmissionUsesBudgetCoordinatorBeforeJob() throws Exception {
        var billing = mock(org.example.seedancegenarate.service.BillingAuthorizationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "billingAuthorization", billing);
        doAnswer(inv -> { ((VideoTask) inv.getArgument(0)).setId(91L); return true; })
                .when(videoTaskService).save(any(VideoTask.class));
        var attempt = new GenerationAttempt(); attempt.setId(701L);
        when(attemptService.stageCurrentAttempt(any(), eq(1), eq(null))).thenReturn(attempt);
        var old = request("budget-request");
        var input = new VideoSubmitService.SubmitRequest(old.userId(), old.provider(), old.model(), old.prompt(),
                old.imageUrls(), old.videoUrls(), old.audioUrls(), old.duration(), old.ratio(), old.megapixels(),
                31L, old.requestId(), old.nodeId());
        var result = service.submit(input);
        var ordered = org.mockito.Mockito.inOrder(billing, attemptService, asyncJobService);
        ordered.verify(billing).freeze(result, BigDecimal.valueOf(100));
        ordered.verify(attemptService).stageCurrentAttempt(result, 1, null);
        ordered.verify(asyncJobService).enqueue(anyString(), anyString(), anyString());
        verify(walletService, never()).freeze(any(), any(), any());
    }

    private VideoSubmitService.SubmitRequest request(String requestId) {
        return new VideoSubmitService.SubmitRequest(8L, "seedance", "seedance-1-0-pro-250528",
                "ocean sunrise", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                5, "16:9", null, null, requestId, null);
    }
}
