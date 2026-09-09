package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.SubmissionNotAcceptedException;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerationAttemptServiceTest {

    @Mock private GenerationAttemptMapper attemptMapper;
    @Mock private VideoTaskMapper taskMapper;
    @Mock private VideoEngineRegistry engineRegistry;
    @Mock private VideoEngine engine;
    @Mock private VideoCompletionProperties completionProperties;
    @Mock private AsyncJobService asyncJobService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private TransactionStatus transactionStatus;

    private GenerationAttemptService service;

    @BeforeEach
    void setUp() {
        service = new GenerationAttemptService(attemptMapper, taskMapper, engineRegistry,
                new ObjectMapper(), completionProperties, transactionTemplate, asyncJobService);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }

    // 【测什么】提交成功同时回写 attempt 和当前 task，Worker 重建的命令不丢 megapixels/指定节点。
    // 【怎么算红】删掉 GenerationAttemptService 中 megapixels 或 requestedNodeId 的命令映射，本测试必须失败。
    @Test
    void acceptedResponsePersistsIdsAndRebuildsCompleteCommand() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(attemptMapper.markSubmitting(attempt.getId())).thenReturn(1);
        when(taskMapper.markAttemptSubmitting(7L, attempt.getId())).thenReturn(1);
        when(engine.submit(any(), any())).thenAnswer(invocation -> {
            VideoEngine.SubmissionObserver observer = invocation.getArgument(1);
            observer.onNodeSelected("gpu-real");
            return SubmitResult.of("remote-1", "gpu-real");
        });
        when(attemptMapper.rememberSubmittingNode(attempt.getId(), "gpu-real")).thenReturn(1);
        when(attemptMapper.markSubmitted(attempt.getId(), "remote-1", "gpu-real")).thenReturn(1);
        when(taskMapper.markAttemptSubmitted(7L, attempt.getId(), "remote-1", "gpu-real")).thenReturn(1);

        assertEquals(GenerationAttemptService.ExecuteResult.SUBMITTED, service.execute(attempt.getId()));

        ArgumentCaptor<GenerateCommand> command = ArgumentCaptor.forClass(GenerateCommand.class);
        verify(engine).submit(command.capture(), any());
        assertEquals("write a film", command.getValue().getPrompt());
        assertEquals(1.25d, command.getValue().getMegapixels());
        assertEquals("gpu-requested", command.getValue().getNodeId());
        assertEquals(attempt.getProviderRequestId(), command.getValue().getProviderRequestId());
        assertEquals(1, command.getValue().getImageUrls().size());
        verify(attemptMapper).markSubmitted(attempt.getId(), "remote-1", "gpu-real");
        verify(taskMapper).markAttemptSubmitted(7L, attempt.getId(), "remote-1", "gpu-real");
    }

    // 【测什么】已 SUBMITTED 的 attempt 被重复消费时直接成功，绝不第二次调供应商。
    // 【怎么算红】把 execute 的 SUBMITTED 短路删掉，verifyNoInteractions(engine) 必须失败。
    @Test
    void alreadySubmittedIsIdempotent() {
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_SUBMITTED);

        assertEquals(GenerationAttemptService.ExecuteResult.SUBMITTED, service.execute(attempt.getId()));

        verifyNoInteractions(engine);
        verify(attemptMapper, never()).markSubmitting(anyLong());
    }

    // 【测什么】接管时看到遗留 SUBMITTING，必须当成“可能已入队”安全停放。
    // 【怎么算红】让 SUBMITTING 分支再次调 engine.submit，verifyNoInteractions(engine) 必须失败。
    @Test
    void inheritedSubmittingNeverSubmitsAgain() {
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_SUBMITTING);
        when(attemptMapper.markUnknown(attempt.getId(), "Worker 在提交中断，结果未知")).thenReturn(1);
        when(taskMapper.markAttemptRecoveryRequired(7L, attempt.getId())).thenReturn(1);

        assertEquals(GenerationAttemptService.ExecuteResult.RECOVERY_REQUIRED, service.execute(attempt.getId()));

        verifyNoInteractions(engine);
        verify(taskMapper).markAttemptRecoveryRequired(7L, attempt.getId());
    }

    // 【测什么】旧 Worker 停放 UNKNOWN 的 CAS 输给已回 PENDING 的安全拒绝路径时，job 继续重试。
    // 【怎么算红】parkUnknown CAS 失败后一律返回 RECOVERY_REQUIRED，会把 PENDING attempt 的 job 收掉。
    @Test
    void unknownParkingRaceWithPendingRemainsSafeRetry() {
        GenerationAttempt submitting = arrange(GenerationAttempt.STATUS_SUBMITTING);
        GenerationAttempt pending = new GenerationAttempt();
        pending.setId(submitting.getId());
        pending.setVideoTaskId(submitting.getVideoTaskId());
        pending.setProvider(submitting.getProvider());
        pending.setStatus(GenerationAttempt.STATUS_PENDING);
        when(attemptMapper.selectById(submitting.getId())).thenReturn(submitting, pending);
        when(attemptMapper.markUnknown(submitting.getId(), "Worker 在提交中断，结果未知")).thenReturn(0);

        assertEquals(GenerationAttemptService.ExecuteResult.SAFE_RETRY,
                service.execute(submitting.getId()));

        verify(taskMapper, never()).markAttemptRecoveryRequired(anyLong(), anyLong());
        verifyNoInteractions(engine);
    }

    // 【测什么】只有供应商明确证明未入队才能回 PENDING 安全退避。
    // 【怎么算红】删掉 SubmissionNotAcceptedException 的专用 catch，结果会变 RECOVERY_REQUIRED，本测试必须失败。
    @Test
    void provenNotAcceptedIsTheOnlySafeRetry() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(attemptMapper.markSubmitting(attempt.getId())).thenReturn(1);
        when(taskMapper.markAttemptSubmitting(7L, attempt.getId())).thenReturn(1);
        when(engine.submit(any(), any())).thenThrow(new SubmissionNotAcceptedException("provider rejected before enqueue"));
        when(attemptMapper.markPending(attempt.getId(), "provider rejected before enqueue")).thenReturn(1);
        when(taskMapper.markAttemptQueued(7L, attempt.getId())).thenReturn(1);

        assertEquals(GenerationAttemptService.ExecuteResult.SAFE_RETRY, service.execute(attempt.getId()));

        verify(attemptMapper).markPending(attempt.getId(), "provider rejected before enqueue");
        verify(taskMapper, never()).markAttemptRecoveryRequired(anyLong(), anyLong());
    }

    // 【测什么】超时/断连不能被当作“未入队”：task 保持 PROCESSING，attempt 进 UNKNOWN。
    // 【怎么算红】把普通 Exception 误分流到 markPending，RECOVERY_REQUIRED 断言或 mapper 验证必须失败。
    @Test
    void timeoutParksUnknownWithoutFailingOrRefundingTask() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(attemptMapper.markSubmitting(attempt.getId())).thenReturn(1);
        when(taskMapper.markAttemptSubmitting(7L, attempt.getId())).thenReturn(1);
        when(engine.submit(any(), any())).thenThrow(new SocketTimeoutException("read timed out"));
        when(attemptMapper.markUnknown(attempt.getId(), "read timed out")).thenReturn(1);
        when(taskMapper.markAttemptRecoveryRequired(7L, attempt.getId())).thenReturn(1);

        assertEquals(GenerationAttemptService.ExecuteResult.RECOVERY_REQUIRED, service.execute(attempt.getId()));

        verify(attemptMapper).markUnknown(attempt.getId(), "read timed out");
        verify(taskMapper).markAttemptRecoveryRequired(7L, attempt.getId());
        verify(asyncJobService).enqueue(GenerationAttemptService.RECOVERY_JOB_TYPE,
                "attempt:10", "{\"attemptId\":10}");
        verify(taskMapper, never()).updateById(any(VideoTask.class));
        assertEquals("PROCESSING", task().getStatus());
        assertEquals(new BigDecimal("12.50"), task().getFreezeAmount());
    }

    // 【测什么】供应商返回 null/空 taskId 也是结果未知，绝不安全重投。
    // 【怎么算红】把空 providerTaskId 当成成功或 SAFE_RETRY，本测试必须失败。
    @Test
    void emptyProviderTaskIdIsUnknown() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(attemptMapper.markSubmitting(attempt.getId())).thenReturn(1);
        when(taskMapper.markAttemptSubmitting(7L, attempt.getId())).thenReturn(1);
        when(engine.submit(any(), any())).thenReturn(SubmitResult.of(""));
        when(attemptMapper.markUnknown(attempt.getId(), "供应商提交响应缺少任务 ID")).thenReturn(1);
        when(taskMapper.markAttemptRecoveryRequired(7L, attempt.getId())).thenReturn(1);

        assertEquals(GenerationAttemptService.ExecuteResult.RECOVERY_REQUIRED, service.execute(attempt.getId()));

        verify(attemptMapper, never()).markSubmitted(anyLong(), any(), any());
    }

    // 【测什么】attempt 在任何外部调用前就持久化唯一 providerRequestId 与指定节点。
    // 【怎么算红】如果 stage 在入库后才生成 providerRequestId，捕获的插入行将为空，本测试必须失败。
    @Test
    void stagingPersistsStableRequestIdBeforeExternalWork() {
        VideoTask task = task();
        task.setCurrentAttemptId(null);
        AtomicReference<String> requestIdAtInsert = new AtomicReference<>();
        AtomicReference<String> requestedNodeAtInsert = new AtomicReference<>();
        doAnswer(invocation -> {
            GenerationAttempt inserted = invocation.getArgument(0);
            requestIdAtInsert.set(inserted.getProviderRequestId());
            requestedNodeAtInsert.set(inserted.getRequestedNodeId());
            inserted.setId(10L);
            return 1;
        }).when(attemptMapper).insert(any(GenerationAttempt.class));
        when(taskMapper.activateAttempt(7L, null, 10L)).thenReturn(1);

        GenerationAttempt staged = service.stageCurrentAttempt(task, 1, " gpu-requested ");

        assertNotNull(staged.getProviderRequestId());
        assertTrue(staged.getProviderRequestId().matches("[0-9a-f-]{36}"));
        assertEquals(staged.getProviderRequestId(), requestIdAtInsert.get());
        assertEquals("gpu-requested", staged.getRequestedNodeId());
        assertEquals("gpu-requested", requestedNodeAtInsert.get());
        assertEquals(GenerationAttempt.STATUS_PENDING, staged.getStatus());
        verify(attemptMapper).insert(staged);
        verify(taskMapper).activateAttempt(7L, null, 10L);
        verifyNoInteractions(engine);
    }

    // 【测什么】读取 task 后 current attempt 被替换，提交权短事务必须回滚并禁止调供应商。
    // 【怎么算红】删掉 claimForSubmit 的 task CAS 或回滚，engine.submit 会被调用，本测试必须失败。
    @Test
    void replacedCurrentAttemptRollsBackClaimBeforeExternalSubmit() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(attemptMapper.markSubmitting(attempt.getId())).thenReturn(1);
        when(taskMapper.markAttemptSubmitting(7L, attempt.getId())).thenReturn(0);

        assertEquals(GenerationAttemptService.ExecuteResult.SAFE_RETRY, service.execute(attempt.getId()));

        verify(taskMapper).markAttemptSubmitting(7L, attempt.getId());
        verify(transactionStatus).setRollbackOnly();
        verify(engine, never()).submit(any(), any());
    }

    // 【测什么】provider 注册缺失发生在提交权 CAS/HTTP 前，必须归类 SAFE_RETRY。
    // 【怎么算红】让 registry.get 异常逸到 Consumer，最后一次会被当“结果未知”永久 RUNNING。
    @Test
    void missingProviderBeforeClaimIsSafeRetry() throws Exception {
        GenerationAttempt attempt = arrangePending();
        when(engineRegistry.get("comfyui")).thenThrow(new IllegalArgumentException("unknown provider"));

        assertEquals(GenerationAttemptService.ExecuteResult.SAFE_RETRY, service.execute(attempt.getId()));

        verify(attemptMapper, never()).markSubmitting(anyLong());
        verify(taskMapper, never()).markAttemptSubmitting(anyLong(), anyLong());
        verify(engine, never()).submit(any(), any());
    }

    // 【测什么】读取 attempt 后任务已切到新轮次，发生在提交权 CAS 前，应判定 SAFE_RETRY/过期轮次。
    // 【怎么算红】让 requireCurrentTask 异常逸出，Consumer 最后一次会误当外部结果未知永久续租。
    @Test
    void replacedTaskBeforeClaimIsSafeRetry() throws Exception {
        GenerationAttempt attempt = arrangePending();
        VideoTask replacement = task();
        replacement.setCurrentAttemptId(11L);
        when(taskMapper.selectById(7L)).thenReturn(replacement);

        assertEquals(GenerationAttemptService.ExecuteResult.SAFE_RETRY, service.execute(attempt.getId()));

        verify(attemptMapper, never()).markSubmitting(anyLong());
        verify(engine, never()).submit(any());
    }

    // 【测什么】只有已回到 PENDING 的 SAFE_RETRY 才能在耗尽时收口为 FAILED。
    // 【怎么算红】删掉 finishFailed 对 markFailed 的状态 CAS，true 断言或 mapper 验证必须失败。
    @Test
    void safeRetryExhaustionClosesPendingAttempt() {
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_PENDING);
        when(attemptMapper.markFailed(attempt.getId(), "retry exhausted")).thenReturn(1);

        assertTrue(service.finishFailed(attempt.getId(), "retry exhausted"));

        verify(attemptMapper).markFailed(attempt.getId(), "retry exhausted");
        verifyNoInteractions(engine, taskMapper);
    }

    @Test
    void unknownAttemptFindsExistingPromptAndReturnsToRunning() throws Exception {
        // 【测什么】恢复 Worker 找到稳定 clientId 对应 prompt 后，只绑定原任务并恢复轮询。
        // 【怎么算红】若 recover 再次调用 submit，或不回写 attempt/task 的 promptId，本测试必须失败。
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
        attempt.setNodeId("gpu-5");
        VideoTask task = task();
        task.setPhase("RECOVERY_REQUIRED");
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(engineRegistry.get("comfyui")).thenReturn(engine);
        when(engine.supportsSubmissionRecovery()).thenReturn(true);
        when(engine.findSubmission(attempt.getProviderRequestId(), "gpu-5"))
                .thenReturn(SubmitResult.of("prompt-1769", "gpu-5"));
        when(attemptMapper.markSubmitted(attempt.getId(), "prompt-1769", "gpu-5")).thenReturn(1);
        when(taskMapper.markAttemptSubmitted(7L, attempt.getId(), "prompt-1769", "gpu-5")).thenReturn(1);

        assertEquals(GenerationAttemptService.RecoveryResult.RECOVERED,
                service.recover(attempt.getId()));

        verify(engine, never()).submit(any(), any());
        verify(taskMapper).markAttemptSubmitted(7L, attempt.getId(), "prompt-1769", "gpu-5");
    }

    @Test
    void reachableNodeWithoutTraceStopsForManualReview() throws Exception {
        // 【测什么】queue/history 可查但找不到稳定请求号时进入人工处置，不把“空”当成未接单重投。
        // 【怎么算红】若 recover 在 null 后调用 submit 或返回 RECOVERED，本测试必须失败。
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
        attempt.setNodeId("gpu-5");
        VideoTask task = task();
        task.setPhase("RECOVERY_REQUIRED");
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(engineRegistry.get("comfyui")).thenReturn(engine);
        when(engine.supportsSubmissionRecovery()).thenReturn(true);
        when(engine.findSubmission(attempt.getProviderRequestId(), "gpu-5")).thenReturn(null);

        assertEquals(GenerationAttemptService.RecoveryResult.MANUAL_REQUIRED,
                service.recover(attempt.getId()));

        verify(engine, never()).submit(any(), any());
        verify(attemptMapper, never()).markSubmitted(anyLong(), any(), any());
    }

    private GenerationAttempt arrangePending() {
        GenerationAttempt attempt = arrange(GenerationAttempt.STATUS_PENDING);
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);
        when(taskMapper.selectById(7L)).thenReturn(task());
        lenient().when(engineRegistry.get("comfyui")).thenReturn(engine);
        lenient().when(engine.supportsSubmissionRecovery()).thenReturn(true);
        return attempt;
    }

    private GenerationAttempt arrange(String status) {
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(10L);
        attempt.setVideoTaskId(7L);
        attempt.setAttemptNo(1);
        attempt.setProvider("comfyui");
        attempt.setProviderRequestId("d5ceef03-fd83-4a04-bb59-0364132663b6");
        attempt.setRequestedNodeId("gpu-requested");
        attempt.setStatus(status);
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);
        if (GenerationAttempt.STATUS_SUBMITTED.equals(status)) {
            attempt.setProviderTaskId("remote-existing");
        }
        return attempt;
    }

    private VideoTask task() {
        VideoTask task = new VideoTask();
        task.setId(7L);
        task.setCurrentAttemptId(10L);
        task.setStatus("PROCESSING");
        task.setProvider("comfyui");
        task.setModel("minimax-h3");
        task.setOutputType("VIDEO");
        task.setPrompt("write a film");
        task.setImages("[\"https://example.invalid/ref.png\"]");
        task.setDuration(8);
        task.setRatio("16:9");
        task.setMegapixels(1.25d);
        task.setFreezeAmount(new BigDecimal("12.50"));
        return task;
    }
}
