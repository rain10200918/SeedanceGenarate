package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskEtaService;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoTaskStatusArbitrationTest {

    private AsyncJobService jobs;
    private TaskStatusTransitioner transitioner;
    private TaskRetryPolicy retryPolicy;
    private VideoTaskServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                VideoTask.class);
    }

    @BeforeEach
    void setUp() {
        jobs = mock(AsyncJobService.class);
        transitioner = mock(TaskStatusTransitioner.class);
        retryPolicy = mock(TaskRetryPolicy.class);
        objectMapper = new ObjectMapper();
        TransactionTemplate template = mock(TransactionTemplate.class);
        org.mockito.Mockito.when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = spy(new VideoTaskServiceImpl(
                mock(VideoDownloadService.class), mock(CostRecordService.class),
                mock(ApplicationEventPublisher.class), jobs, mock(TaskEtaService.class),
                transitioner, retryPolicy, mock(WalletService.class), mock(PricingService.class),
                mock(AdmissionControl.class), objectMapper, template));
    }

    @Test
    void finalizingSuccessWinsOverLateFailedAndLost() {
        // 【测什么】SUCCESS 已把 phase 赢到 FINALIZING 后，迟到 FAILED/LOST 都不得改判或重投。
        // 【怎么算红】移除 FINALIZING 仲裁守卫会调用 transitioner/retryPolicy。
        VideoTask task = task("FINALIZING", 7L, "remote-7");

        service.updateStatus(task, RemoteStatus.failed("late failure"));
        service.updateStatus(task, RemoteStatus.lost("late lost"));

        verifyNoInteractions(transitioner, retryPolicy);
    }

    @Test
    void emptySuccessUrlCannotClaimFinalizing() {
        // 【测什么】空成功地址在 FINALIZING CAS 前即被拒绝，后续有效 SUCCESS 仍能赢。
        // 【怎么算红】若先 stage 再校验，update/enqueue 会发生且任务只能靠转存失败收口。
        VideoTask task = task("RUNNING", 7L, "remote-7");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(task, RemoteStatus.success("  ")));

        verify(service, never()).update(any(Wrapper.class));
        verifyNoInteractions(jobs);
    }

    @Test
    void successStagesIdentityRichFinalizeJob() {
        // 【测什么】成功仲裁 CAS 赢后，finalize payload 固化 attempt/provider/phase/retry 全身份。
        // 【怎么算红】漏掉 phase/retry 会让旧 finalize job 在任务回到 RUNNING 后错误转存。
        VideoTask task = task("RUNNING", 7L, "remote-7");
        doReturn(true).when(service).update(any(Wrapper.class));

        service.updateStatus(task, RemoteStatus.success("https://provider.invalid/a.mp4?sig=secret"));

        verify(jobs).enqueue(eq(VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE),
                eq("task:42:attempt:7"), contains("\"expectedPhase\":\"FINALIZING\""));
        verify(jobs).enqueue(eq(VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE),
                eq("task:42:attempt:7"), contains("\"expectedRetryCount\":0"));
    }

    @Test
    void staleSuccessThatLosesIdentityCasCannotEnqueueFinalize() {
        // 【测什么】poll HTTP 期间新 attempt 激活后，旧 SUCCESS 的 identity CAS 输且不入队。
        // 【怎么算红】若 SUCCESS 只按 taskId 写，旧产物会进入 finalize 并覆盖新轮。
        VideoTask oldSnapshot = task("RUNNING", 7L, "remote-7");
        doReturn(false).when(service).update(any(Wrapper.class));

        service.updateStatus(oldSnapshot, RemoteStatus.success("https://provider.invalid/old.mp4"));

        verify(jobs, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    void finalizePayloadRoundTripsAllJsonControlCharacters() throws Exception {
        // 【测什么】remote URL/provider identity 中的换行、制表与 U+0001 经 ObjectMapper 可无损解析。
        // 【怎么算红】手拼 JSON 只转义引号/反斜杠时，消费者 readTree 会失败并吞掉 finalize job。
        VideoTask task = task("RUNNING", 7L, "remote\n\r\t\u0001-id");
        task.setProvider("see\tdance\u0001");
        String url = "https://provider.invalid/a\n\r\t\u0001.mp4?sig=secret";
        doReturn(true).when(service).update(any(Wrapper.class));
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);

        service.updateStatus(task, RemoteStatus.success(url));

        verify(jobs).enqueue(eq(VideoTaskServiceImpl.JOB_TYPE_TASK_FINALIZE),
                eq("task:42:attempt:7"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertTrue(json.get("remoteVideoUrl").asText().equals(url));
        assertTrue(json.get("expectedProviderTaskId").asText().equals(task.getProviderTaskId()));
        assertTrue(json.get("expectedProvider").asText().equals(task.getProvider()));
    }

    @Test
    void payloadSerializationFailureRollsBackFinalizingClaim() throws Exception {
        // 【测什么】FINALIZING CAS 后 payload 序列化失败必须回滚同一事务。
        // 【怎么算红】在事务外序列化或吞异常，会留下 FINALIZING 但没有 TASK_FINALIZE 的永久卡单。
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        VideoTaskServiceImpl local = spy(new VideoTaskServiceImpl(
                mock(VideoDownloadService.class), mock(CostRecordService.class),
                mock(ApplicationEventPublisher.class), jobs, mock(TaskEtaService.class),
                transitioner, retryPolicy, mock(WalletService.class), mock(PricingService.class),
                mock(AdmissionControl.class), broken, new TransactionTemplate(manager)));
        doReturn(true).when(local).update(any(Wrapper.class));

        assertThrows(IllegalStateException.class,
                () -> local.updateStatus(task("RUNNING", 7L, "remote-7"),
                        RemoteStatus.success("https://provider.invalid/a.mp4")));

        verify(manager).rollback(status);
        verify(jobs, never()).enqueue(anyString(), anyString(), anyString());
    }

    private VideoTask task(String phase, Long attemptId, String providerTaskId) {
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setBizTaskId("tsk_42");
        task.setStatus("PROCESSING");
        task.setPhase(phase);
        task.setRetryCount(0);
        task.setCurrentAttemptId(attemptId);
        task.setProviderTaskId(providerTaskId);
        task.setProvider("seedance");
        return task;
    }
}
