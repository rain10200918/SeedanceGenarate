package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账务补偿的「毒行」守卫。
 * <p>
 * 背景：补偿候选是一条 {@code ORDER BY v.id ASC LIMIT 100} 的队列，且带 7 天窗口。
 * 补不好的行（task 764：冻结额被 D-016 的 bug 挪走，池子里没钱可退）id 最小，
 * <b>7 天里每一轮都排在队头</b>——攒到 100 条，新任务就再也补不上账；
 * 而 7 天一过它又静默掉出候选集，不再重试也不再告警，钱永远错着且没人知道。
 */
class WalletCompensationQuarantineTest {

    private static final int QUARANTINE_AFTER = 10;
    private static final int RETRY_EVERY = 120;

    private VideoTaskService videoTaskService;
    private WalletService walletService;
    private TaskReconcileTask reconcile;

    @BeforeEach
    void setUp() {
        videoTaskService = mock(VideoTaskService.class);
        walletService = mock(WalletService.class);
        DistributedLockProperties lockProperties = new DistributedLockProperties();
        lockProperties.setEnabled(false);

        reconcile = new TaskReconcileTask(videoTaskService, mock(VideoEngineRegistry.class),
                mock(VideoTaskPoller.class), mock(TaskStatusTransitioner.class), walletService,
                mock(TaskRetryPolicy.class), mock(DistributedLock.class), lockProperties);
        ReflectionTestUtils.setField(reconcile, "maxAgeHours", 24L);
        ReflectionTestUtils.setField(reconcile, "timeoutMinutes", 60L);
        ReflectionTestUtils.setField(reconcile, "submitStallMinutes", 10L);
        ReflectionTestUtils.setField(reconcile, "defaultProvider", "comfyui");
        ReflectionTestUtils.setField(reconcile, "roundBudgetMs", 60_000L);
        // 另外三个分支没活，只留账务补偿这一支
        when(videoTaskService.list(any(Wrapper.class))).thenReturn(List.of());
    }

    private VideoTask poisonTask() {
        VideoTask t = new VideoTask();
        t.setId(764L);
        t.setBizTaskId("tsk_e2d2a08a");
        t.setUserId(22L);
        t.setStatus("FAILED");
        t.setFreezeAmount(new BigDecimal("2.40"));
        return t;
    }

    /**
     * 跑 n 轮，账务补偿每轮都拿到同一条补不好的任务。
     * <p>
     * mock 必须<b>真的按 excludeIds 过滤</b>——否则它会在被排除之后照旧把毒行发回来，
     * 测试就再也分不出「排除了」和「没排除」，隔离重试那条路径也就白测了。
     */
    private void runRounds(int n) {
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any()))
                .thenAnswer(inv -> {
                    Collection<Long> excluded = inv.getArgument(1);
                    return excluded != null && excluded.contains(764L)
                            ? List.of() : List.of(poisonTask());
                });
        doThrow(new IllegalStateException("钱包冻结余额不足，无法解冻: userId=22, taskId=764"))
                .when(walletService).release(any(), any(), anyLong());
        for (int i = 0; i < n; i++) {
            reconcile.reconcileOverdueTasks();
        }
    }

    /** 一直跑到对账轮次正好等于 target（隔离重试是按轮次取模触发的，差一轮就测不到） */
    private void runUntilRound(long target) {
        java.util.concurrent.atomic.AtomicLong rounds =
                (java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(
                        reconcile, "reconcileRounds");
        while (rounds.get() < target) {
            reconcile.reconcileOverdueTasks();
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<Long> lastExcludeIds() {
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(videoTaskService, atLeastOnce())
                .findTerminalMissingWalletTransition(eq(100), captor.capture());
        return captor.getValue();
    }

    @Test
    void poisonRowIsExcludedFromTheQueryNotJustSkippedInTheLoop() {
        // 【测什么】连续失败到门槛后，毒行的 id 被传进查询的排除集合
        // 【怎么算红】只在循环里 continue、不告诉 SQL —— 毒行照旧占着 ORDER BY id ASC LIMIT 100
        //            的队头，攒到 100 条之后所有新任务的退款全部补不上，而日志里只有那几条
        //            熟悉的 ERROR，看不出「别人也被堵住了」
        runRounds(QUARANTINE_AFTER + 1);

        assertTrue(lastExcludeIds().contains(764L),
                "补不好的 764 必须从查询里排除，实际排除集合=" + lastExcludeIds());
    }

    @Test
    void healthyRunPassesAnEmptyExcludeSet() {
        // 【测什么】没有毒行时排除集合是空的（SQL 侧的 <if> 靠它整段不拼——NOT IN () 是语法错）
        // 【怎么算红】随便塞点东西进去 —— 正常任务被排除，它的退款永远补不上，
        //            而且这条路径上没有任何告警会响
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any()))
                .thenReturn(List.of());

        reconcile.reconcileOverdueTasks();

        assertTrue(lastExcludeIds().isEmpty(), "实际=" + lastExcludeIds());
    }

    @Test
    void quarantinedRowIsNotYetExcludedBelowTheThreshold() {
        // 【测什么】门槛之下不隔离 —— 几轮 DB 抖动这种瞬时故障不该把正常任务踢出主查询
        // 【怎么算红】一失败就隔离 —— 数据库抖一下，那一批任务全被降级成每小时重试一次，
        //            用户的退款从 30 秒变成一小时
        runRounds(QUARANTINE_AFTER - 1);

        assertFalse(lastExcludeIds().contains(764L),
                "只失败了 " + (QUARANTINE_AFTER - 1) + " 次，还不该隔离");
    }

    @Test
    void quarantinedRowIsRetriedHourlyOutsideTheSevenDayWindow() {
        // 【测什么】**本次改动的核心**：隔离行改由 listByIds 按 id 直取来重试，
        //          不再经过那条带 7 天窗口的查询
        // 【怎么算红】隔离后就再也不碰它 —— 和改动前的静默放弃是同一个结果：
        //          7 天一过，钱永远错着，而且每日对账（总资产口径）依然全绿
        runRounds(QUARANTINE_AFTER);
        verify(videoTaskService, never()).listByIds(any());

        when(videoTaskService.listByIds(any())).thenReturn(List.of(poisonTask()));
        // 补到第 RETRY_EVERY 轮
        for (int i = QUARANTINE_AFTER; i < RETRY_EVERY; i++) {
            reconcile.reconcileOverdueTasks();
        }

        verify(videoTaskService).listByIds(any());
    }

    @Test
    void fixingTheDataLiftsTheQuarantineWithoutARestart() {
        // 【测什么】人把数据修好后自动解除隔离：这一次 release 不抛了 → 计数清零 → 不再被排除
        // 【怎么算红】隔离是单向的 —— 人修完数据还得重启进程才恢复，而没人知道要重启，
        //            于是这条任务就一直挂在「已隔离」里，看起来像还没修好
        runRounds(QUARANTINE_AFTER + 1);
        assertTrue(lastExcludeIds().contains(764L), "前置条件：先得真的被隔离了");

        org.mockito.Mockito.reset(walletService); // 数据修好了，release 不再抛
        when(videoTaskService.listByIds(any())).thenReturn(List.of(poisonTask()));
        for (int i = QUARANTINE_AFTER + 1; i <= RETRY_EVERY; i++) {
            reconcile.reconcileOverdueTasks();
        }
        reconcile.reconcileOverdueTasks(); // 再跑一轮看排除集合

        verify(walletService, atLeastOnce()).release(eq(22L), any(), eq(764L));
        assertTrue(lastExcludeIds().isEmpty(), "补偿成功后必须解除隔离，实际=" + lastExcludeIds());
    }

    @Test
    void deletedTaskIsDroppedFromQuarantineInsteadOfSpinningForever() {
        // 【测什么】隔离中的任务被删掉后，它的 id 从隔离集合里移出
        // 【怎么算红】留在集合里 —— 每小时空转一次、永远排除一个不存在的 id，
        //            集合只增不减，长期就是一个慢速内存泄漏
        runRounds(QUARANTINE_AFTER + 1);
        when(videoTaskService.listByIds(any())).thenReturn(List.of()); // 任务已不存在

        for (int i = QUARANTINE_AFTER + 1; i <= RETRY_EVERY; i++) {
            reconcile.reconcileOverdueTasks();
        }
        reconcile.reconcileOverdueTasks();

        assertTrue(lastExcludeIds().isEmpty(), "任务不存在就该解除隔离，实际=" + lastExcludeIds());
    }

    @Test
    void stillPoisonRowStaysExcludedOnTheVeryRoundItIsRetried() {
        // 【测什么】隔离重试那一轮里，仍然补不好的行**必须还在主查询的排除集合里**
        // 【怎么算红】retryQuarantined 改了传进来的那个 set（而它紧接着就被当 excludeIds 用）——
        //            每 120 轮排除会被自己悄悄解除一次，毒行重新回到 ORDER BY id ASC 的队头，
        //            同一轮里还被补偿两遍。隔离在这一轮等于没有。
        runRounds(QUARANTINE_AFTER + 1);
        when(videoTaskService.listByIds(any())).thenReturn(List.of(poisonTask()));

        // 必须**正好停在**第 RETRY_EVERY 轮：多跑一轮，排除集合就被重算、看不出被清掉过
        runUntilRound(RETRY_EVERY);

        assertTrue(lastExcludeIds().contains(764L),
                "重试那一轮排除集合不该被清掉，实际=" + lastExcludeIds());
    }

    @Test
    void listByIdsFailureDoesNotBlockTheMainCompensationPass() {
        // 【测什么】隔离重试拉取失败时，主补偿分支照常跑
        // 【怎么算红】异常冒出去 —— 隔离行拉不到就整个账务补偿分支瘫掉，
        //            所有人的退款一起停摆，而这只是个「顺便重试一下」的支线
        runRounds(QUARANTINE_AFTER + 1);
        when(videoTaskService.listByIds(any())).thenThrow(new RuntimeException("DB 连接中断"));

        for (int i = QUARANTINE_AFTER + 1; i <= RETRY_EVERY; i++) {
            reconcile.reconcileOverdueTasks();
        }

        // 主查询在隔离重试失败之后仍然被调用过
        verify(videoTaskService, atLeastOnce()).findTerminalMissingWalletTransition(eq(100), any());
    }

    @Test
    void excludeListIsCappedSoTheQueryDoesNotDegenerate() {
        // 【测什么】隔离集合膨胀时 NOT IN 列表被截断到 200，且保留 id 最小的那批
        // 【怎么算红】不截断 —— 隔离几千条时 NOT IN 列表长到拖慢查询本身，
        //            而真正堵住 ORDER BY id ASC 队头的只是最小那批，多传的全是无用负担
        @SuppressWarnings("unchecked")
        java.util.Map<Long, Integer> failures =
                (java.util.Map<Long, Integer>) ReflectionTestUtils.getField(
                        reconcile, "walletCompensationFailures");
        for (long id = 1; id <= 500; id++) {
            failures.put(id, QUARANTINE_AFTER);
        }
        when(videoTaskService.findTerminalMissingWalletTransition(eq(100), any()))
                .thenReturn(List.of());

        reconcile.reconcileOverdueTasks();

        Collection<Long> excluded = lastExcludeIds();
        assertEquals(200, excluded.size(), "超量必须截断");
        assertTrue(excluded.contains(1L), "保留的应是 id 最小的那批（它们才在队头）");
        assertFalse(excluded.contains(500L), "id 最大的那批不该占用名额");
    }
}
