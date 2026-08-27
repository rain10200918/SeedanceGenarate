package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单轮对账的时间预算守卫。
 * <p>
 * 背景：四个分支串行共用一把 120 秒的锁。改动前没有任何时间上限——两条落在 hang 住节点上的
 * 任务就够把租约跑爆，于是（一）另一个实例拿到锁并发进来，（二）排在最后的账务补偿
 * 这一轮根本轮不到执行。而钱没退回去是用户直接看得见的。
 */
class TaskReconcileBudgetTest {

    private VideoTaskService videoTaskService;
    private VideoTaskPoller videoTaskPoller;
    private TaskStatusTransitioner taskStatusTransitioner;
    private WalletService walletService;
    private TaskRetryPolicy taskRetryPolicy;
    private VideoEngineRegistry videoEngineRegistry;
    private VideoEngine engine;
    private TaskReconcileTask reconcile;

    @BeforeEach
    void setUp() {
        videoTaskService = mock(VideoTaskService.class);
        videoTaskPoller = mock(VideoTaskPoller.class);
        taskStatusTransitioner = mock(TaskStatusTransitioner.class);
        walletService = mock(WalletService.class);
        taskRetryPolicy = mock(TaskRetryPolicy.class);
        videoEngineRegistry = mock(VideoEngineRegistry.class);
        engine = mock(VideoEngine.class);
        when(videoEngineRegistry.get(any())).thenReturn(engine);

        DistributedLockProperties lockProperties = new DistributedLockProperties();
        lockProperties.setEnabled(false); // 单实例路径：不经 Redis，直接执行

        reconcile = new TaskReconcileTask(videoTaskService, videoEngineRegistry, videoTaskPoller,
                taskStatusTransitioner, walletService, taskRetryPolicy,
                mock(DistributedLock.class), lockProperties);
        ReflectionTestUtils.setField(reconcile, "maxAgeHours", 24L);
        ReflectionTestUtils.setField(reconcile, "timeoutMinutes", 60L);
        ReflectionTestUtils.setField(reconcile, "submitStallMinutes", 10L);
        ReflectionTestUtils.setField(reconcile, "defaultProvider", "comfyui");
    }

    private List<VideoTask> tasks(int count) {
        List<VideoTask> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            VideoTask t = new VideoTask();
            t.setId((long) i);
            t.setBizTaskId("tsk_" + i);
            t.setUserId(22L);
            t.setStatus("FAILED");
            t.setProvider("comfyui");
            t.setFreezeAmount(new BigDecimal("2.40"));
            list.add(t);
        }
        return list;
    }

    @Test
    void everyBranchStillMakesProgressWhenBudgetIsZero() throws Exception {
        // 【测什么】预算为 0 时每个分支仍然做成第一条 —— 不许出现「每轮零进展」的活锁
        // 【怎么算红】把预算检查放在处理之前而不带 processed>0 的守卫 —— 一旦有人把预算配小，
        //            对账每轮扫描、每轮一条都不做，任务永远卡在 PROCESSING，且日志上看不出异常
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 0L);
        when(videoTaskService.list(any(Wrapper.class))).thenReturn(tasks(3), tasks(3), tasks(3));
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any())).thenReturn(tasks(3));
        when(engine.poll(any())).thenReturn(RemoteStatus.processing());

        reconcile.reconcileOverdueTasks();

        verify(videoTaskPoller, times(1)).advanceTask(any());              // 分支①
        verify(taskRetryPolicy, times(1)).retryOrFail(any(), any(), any()); // 分支②
        verify(taskStatusTransitioner, times(1)).markTimedOut(anyLong(), any()); // 分支③
        verify(walletService, times(1)).release(any(), any(), anyLong());   // 分支④
    }

    @Test
    void slowFirstBranchDoesNotStarveWalletCompensation() throws Exception {
        // 【测什么】**本次改动的核心**：分支①被慢节点拖住并耗尽自己那份预算后，
        //          排在最后的账务补偿仍然拿到<b>满额</b>预算，把 3 条全部补完
        // 【怎么算红】四个分支共用一个总预算 —— 分支①一慢，账务补偿每轮只补得动 1 条，
        //          用户的退款排队等好几轮；节点持续不健康时等于永久欠着
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 400L); // 每分支 100ms
        when(videoTaskService.list(any(Wrapper.class)))
                .thenReturn(tasks(3), List.of(), List.of()); // 只有分支①有活
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any())).thenReturn(tasks(3));
        // 每条 150ms > 100ms 预算：分支①做完第一条就必须停
        doAnswer(inv -> {
            Thread.sleep(150);
            return null;
        }).when(videoTaskPoller).advanceTask(any());

        reconcile.reconcileOverdueTasks();

        verify(videoTaskPoller, times(1)).advanceTask(any());
        verify(walletService, times(3)).release(any(), any(), anyLong());
    }

    @Test
    void budgetIsStructurallyCappedByLockTtl() {
        // 【测什么】预算被锁 TTL 的一半夹死，配置写多大都突破不了
        // 【怎么算红】直接采信配置值 —— 一轮跑过 120 秒租约就到期，另一个实例拿到锁并发进来，
        //            两台同时推进同一批任务（fixedDelay 只防同实例重入，不防跨实例）
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 999_999L);

        long perBranch = reconcile.perBranchBudgetMs();

        assertEquals(15_000L, perBranch, "60s 总预算 ÷ 4 个分支");
        assertTrue(perBranch * 4 <= 60_000L, "一轮四个分支加起来不得超过锁 TTL 的一半");
    }

    @Test
    void normalBudgetProcessesEveryTask() throws Exception {
        // 【测什么】预算充足时行为与改动前一致：该处理的一条都不少
        // 【怎么算红】预算逻辑误伤正常路径 —— 每轮只推进一条任务，其余全被"留待下一轮"，
        //            吞吐掉到 1/100，而日志里只有一句 warn，很容易被当成正常
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 60_000L);
        when(videoTaskService.list(any(Wrapper.class))).thenReturn(tasks(3), List.of(), List.of());
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any())).thenReturn(List.of());

        reconcile.reconcileOverdueTasks();

        verify(videoTaskPoller, times(3)).advanceTask(any());
        verify(walletService, never()).release(any(), any(), anyLong());
    }

    @Test
    void successfulTaskSettlesInsteadOfReleasing() throws Exception {
        // 【测什么】预算改动没有动账务分支的分流逻辑：SUCCESS 走 settle、FAILED 走 release
        // 【怎么算红】两者接反 —— 成功的任务被解冻（用户白嫖）、失败的任务被结算（乱扣钱）
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 60_000L);
        List<VideoTask> succeeded = tasks(1);
        succeeded.get(0).setStatus("SUCCESS");
        when(videoTaskService.list(any(Wrapper.class))).thenReturn(List.of(), List.of(), List.of());
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any())).thenReturn(succeeded);

        reconcile.reconcileOverdueTasks();

        verify(walletService).settle(eq(22L), any(), eq(1L));
        verify(walletService, never()).release(any(), any(), anyLong());
    }
}
