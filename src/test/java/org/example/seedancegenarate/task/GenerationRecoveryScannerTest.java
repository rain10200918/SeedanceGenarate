package org.example.seedancegenarate.task;

import org.example.seedancegenarate.engine.comfyui.ComfyUiFleet;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationRecoveryScannerTest {

    @Test
    void legacyUnknownWithoutRecoveryJobIsBackfilled() {
        // 【测什么】升级前已经停放的 SUBMIT_UNKNOWN 会补建恢复作业，不依赖旧 submit job 再执行。
        // 【怎么算红】删掉 scanner 的 missing-job 分支，attempt 1769 这类存量任务会永久卡住。
        Fixture f = fixture();
        GenerationAttempt attempt = unknown();
        when(f.mapper.findSubmitUnknown(100)).thenReturn(List.of(attempt));
        when(f.jobs.find(GenerationAttemptService.RECOVERY_JOB_TYPE, "attempt:4")).thenReturn(null);

        f.scanner.scan();

        verify(f.attempts).ensureRecoveryScheduled(4L);
    }

    @Test
    void deadRecoveryIsReopenedOnlyAfterNodeComesBack() {
        // 【测什么】节点离线导致恢复作业 DEAD 后，健康快照恢复在线会重新唤醒；离线时不空转打 502。
        // 【怎么算红】忽略节点健康会永久不重开或每轮轰击故障节点，本测试至少一边失败。
        Fixture f = fixture();
        GenerationAttempt attempt = unknown();
        AsyncJob dead = new AsyncJob();
        dead.setStatus(AsyncJob.STATUS_DEAD);
        when(f.mapper.findSubmitUnknown(100)).thenReturn(List.of(attempt));
        when(f.jobs.find(GenerationAttemptService.RECOVERY_JOB_TYPE, "attempt:4")).thenReturn(dead);
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId("gpu-5");
        node.setBaseUrl("http://gpu-5");
        node.setEnabled(true);
        when(f.fleet.node("gpu-5")).thenReturn(org.example.seedancegenarate.engine.comfyui.NodeState.initial(node));

        f.scanner.scan();

        verify(f.attempts).ensureRecoveryScheduled(4L);
    }

    @Test
    void completedManualRecoveryIsNotReopenedForever() {
        // 【测什么】查询为空后已完成的恢复 job 表示等待人工，不被周期扫描反复重开。
        // 【怎么算红】若 SUCCEEDED 也重开，后台会永久重复拉取几 MB 的 queue/history。
        Fixture f = fixture();
        GenerationAttempt attempt = unknown();
        AsyncJob completed = new AsyncJob();
        completed.setStatus(AsyncJob.STATUS_SUCCEEDED);
        when(f.mapper.findSubmitUnknown(100)).thenReturn(List.of(attempt));
        when(f.jobs.find(GenerationAttemptService.RECOVERY_JOB_TYPE, "attempt:4")).thenReturn(completed);

        f.scanner.scan();

        verify(f.attempts, never()).ensureRecoveryScheduled(4L);
    }

    private GenerationAttempt unknown() {
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(4L);
        attempt.setStatus(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
        attempt.setNodeId("gpu-5");
        return attempt;
    }

    private Fixture fixture() {
        GenerationAttemptMapper mapper = mock(GenerationAttemptMapper.class);
        AsyncJobService jobs = mock(AsyncJobService.class);
        GenerationAttemptService attempts = mock(GenerationAttemptService.class);
        ComfyUiFleet fleet = mock(ComfyUiFleet.class);
        return new Fixture(mapper, jobs, attempts, fleet,
                new GenerationRecoveryScanner(mapper, jobs, attempts, fleet));
    }

    private record Fixture(GenerationAttemptMapper mapper, AsyncJobService jobs,
                           GenerationAttemptService attempts, ComfyUiFleet fleet,
                           GenerationRecoveryScanner scanner) {
    }
}
