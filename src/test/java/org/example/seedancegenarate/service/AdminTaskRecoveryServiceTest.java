package org.example.seedancegenarate.service;

import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminTaskRecoveryServiceTest {
    private final VideoTaskMapper tasks = mock(VideoTaskMapper.class);
    private final GenerationAttemptMapper attempts = mock(GenerationAttemptMapper.class);
    private final AsyncJobService jobs = mock(AsyncJobService.class);
    private final GenerationAttemptService generationAttempts = mock(GenerationAttemptService.class);
    private final TaskStatusTransitioner transitions = mock(TaskStatusTransitioner.class);
    private final VideoEngineRegistry engines = mock(VideoEngineRegistry.class);
    private final VideoEngine comfy = mock(VideoEngine.class);
    private AdminTaskRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AdminTaskRecoveryService(tasks, attempts, jobs, generationAttempts, transitions, engines);
        when(engines.get("comfyui")).thenReturn(comfy);
        when(comfy.supportsSubmissionRecovery()).thenReturn(true);
    }

    @Test
    void diagnosisShowsAttemptNodeJobAndFrozenAmount() {
        // 【测什么】管理员看到的是可处置证据，不只是 taskId 和“卡死”两个字。
        // 【怎么算红】任一 attempt/node/request/job/freeze 映射被删，字段断言必须失败。
        arrangeRecovery();
        AsyncJob job = new AsyncJob();
        job.setStatus(AsyncJob.STATUS_DEAD);
        job.setAttempts(5);
        job.setMaxAttempts(5);
        job.setLastError("502 Bad Gateway");
        when(jobs.find(GenerationAttemptService.RECOVERY_JOB_TYPE, "attempt:4")).thenReturn(job);

        var view = service.find("tsk-1769");

        assertEquals(4L, view.attemptId());
        assertEquals("gpu-5", view.nodeId());
        assertEquals("request-1", view.providerRequestId());
        assertEquals("DEAD", view.recoveryJob().status());
        assertEquals(new BigDecimal("0.10"), view.freezeAmount());
    }

    @Test
    void bindUsesAttemptCasInsteadOfResubmitting() {
        // 【测什么】人工提供 promptId 只绑定当前 UNKNOWN attempt，不触发新 submit。
        // 【怎么算红】若绕过 bindRecovered 直接改 task 或重投，verify 必须失败。
        arrangeRecovery();
        when(generationAttempts.bindRecovered(4L, "prompt-1", "gpu-5")).thenReturn(true);

        service.bind("tsk-1769", "prompt-1", "gpu-5");

        verify(generationAttempts).bindRecovered(4L, "prompt-1", "gpu-5");
        verify(transitions, never()).markRecoveryFailedIfCurrent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void terminateUsesTheOnlyFailureBoundaryAndClosesAttempt() {
        // 【测什么】管理员终止必须同时 CAS 任务和 UNKNOWN attempt，任务终态入口负责解冻与释放槽位。
        // 【怎么算红】若直接 update video_task 或漏关 attempt，两个 verify 至少一个失败。
        arrangeRecovery();
        when(transitions.markRecoveryFailedIfCurrent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("管理员确认 GPU 节点未接单"))).thenReturn(true);
        when(attempts.markUnknownFailed(4L, "管理员确认 GPU 节点未接单")).thenReturn(1);

        service.terminate("tsk-1769", "管理员确认 GPU 节点未接单");

        verify(transitions).markRecoveryFailedIfCurrent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("管理员确认 GPU 节点未接单"));
        verify(attempts).markUnknownFailed(4L, "管理员确认 GPU 节点未接单");
    }

    @Test
    void nonRecoveryTaskCannotBeTerminatedFromRecoveryConsole() {
        // 【测什么】成功、普通运行或新 attempt 不能被旧恢复页面误杀。
        // 【怎么算红】删掉 phase/currentAttempt 校验后将不再抛冲突且会调用终态入口。
        VideoTask task = task();
        task.setPhase("RUNNING");
        when(tasks.findByBusinessTaskId("tsk-1769")).thenReturn(task);

        assertThrows(RuntimeException.class,
                () -> service.terminate("tsk-1769", "管理员确认 GPU 节点未接单"));

        verify(transitions, never()).markRecoveryFailedIfCurrent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void lookupFoundOnTheNodeBindsThroughTheAutomaticRecoveryPath() throws Exception {
        // 【测什么】「再查一次」复用 GenerationAttemptService.recover（查到才绑定），不自己写第二套查找/绑定。
        // 【怎么算红】改成直接调 bindRecovered 或 submit：verify(recover) 失败；或 found 不为 true。
        arrangeRecovery();
        when(generationAttempts.recover(4L)).thenReturn(GenerationAttemptService.RecoveryResult.RECOVERED);

        var result = service.lookup("tsk-1769");

        assertTrue(result.found());
        assertTrue(result.message().contains("gpu-5"), "人话里要点名是哪台节点，实际=" + result.message());
        verify(generationAttempts).recover(4L);
        verify(generationAttempts, never()).bindRecovered(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void lookupNotFoundSpeaksPlainWordsAndNeverResubmits() throws Exception {
        // 【测什么】查不到 = 告诉运营「节点上没有记录、可以判定失败退款」；不是抛错，也不重投。
        // 【怎么算红】把查不到当成异常抛出、或文案里出现 attempt/request/UNKNOWN 这类词，断言失败。
        arrangeRecovery();
        when(generationAttempts.recover(4L)).thenReturn(GenerationAttemptService.RecoveryResult.MANUAL_REQUIRED);

        var result = service.lookup("tsk-1769");

        assertFalse(result.found());
        assertFalse(result.settled());
        assertTrue(result.message().contains("没有这条任务的记录"), result.message());
        assertPlainLanguage(result.message());
    }

    @Test
    void lookupNodeUnreachableDoesNotLeakTheExceptionText() throws Exception {
        // 【测什么】节点连不上时给运营的是「现在连不上节点，稍后再点」，不是异常里的 URL/堆栈。
        // 【怎么算红】把 e.getMessage() 拼进 message，或让异常直接抛出去，断言失败。
        arrangeRecovery();
        when(generationAttempts.recover(4L)).thenThrow(new RuntimeException("connect to http://10.0.0.8:8188 timed out"));

        var result = service.lookup("tsk-1769");

        assertFalse(result.found());
        assertTrue(result.message().contains("连不上"), result.message());
        assertFalse(result.message().contains("10.0.0.8"), "异常文本泄漏: " + result.message());
        assertFalse(result.message().contains("http"), "异常文本泄漏: " + result.message());
    }

    @Test
    void lookupOnAnAlreadyHandledTaskSaysSoInsteadOfThrowing() throws Exception {
        // 【测什么】任务已经不在恢复态（别人刚处理过）时，「再查一次」返回 settled，不去碰节点也不抛冲突。
        // 【怎么算红】去掉 inRecovery 前置判断：recover 会被调用，verify(never) 失败。
        VideoTask done = task();
        done.setStatus("SUCCESS");
        done.setPhase(null);
        when(tasks.findByBusinessTaskId("tsk-1769")).thenReturn(done);

        var result = service.lookup("tsk-1769");

        assertTrue(result.settled());
        verify(generationAttempts, never()).recover(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void lookupWithoutNodeOrRequestIdCannotSearchAndSaysWhy() throws Exception {
        // 【测什么】提交时没记下节点/请求号，系统没法去查：直接给人话，不调 recover。
        // 【怎么算红】去掉 noTrace 分支：recover 被调用，verify(never) 失败。
        arrangeRecovery();
        GenerationAttempt bare = new GenerationAttempt();
        bare.setId(4L);
        bare.setVideoTaskId(1769L);
        bare.setStatus(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
        when(attempts.selectById(4L)).thenReturn(bare);

        var result = service.lookup("tsk-1769");

        assertFalse(result.found());
        assertTrue(result.message().contains("没记下"), result.message());
        verify(generationAttempts, never()).recover(org.mockito.ArgumentMatchers.anyLong());
    }

    /** 给运营看的句子里不许出现这些词——它们属于「技术细节」折叠区 */
    private static void assertPlainLanguage(String message) {
        for (String jargon : new String[]{"attempt", "Attempt", "request", "UNKNOWN", "prompt", "Prompt", "queue", "history", "轮询", "CAS"}) {
            assertFalse(message.contains(jargon), "人话里混进了术语「" + jargon + "」: " + message);
        }
    }

    private void arrangeRecovery() {
        when(tasks.findByBusinessTaskId("tsk-1769")).thenReturn(task());
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(4L);
        attempt.setVideoTaskId(1769L);
        attempt.setStatus(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
        attempt.setProviderRequestId("request-1");
        attempt.setRequestedNodeId("gpu-5");
        attempt.setNodeId("gpu-5");
        attempt.setLastError("502 Bad Gateway");
        when(attempts.selectById(4L)).thenReturn(attempt);
    }

    private VideoTask task() {
        VideoTask task = new VideoTask();
        task.setId(1769L);
        task.setBizTaskId("tsk-1769");
        task.setStatus("PROCESSING");
        task.setPhase("RECOVERY_REQUIRED");
        task.setProvider("comfyui");
        task.setCurrentAttemptId(4L);
        task.setFreezeAmount(new BigDecimal("0.10"));
        return task;
    }
}
