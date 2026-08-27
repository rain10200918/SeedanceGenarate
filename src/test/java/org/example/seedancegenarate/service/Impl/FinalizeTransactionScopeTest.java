package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.TaskEtaService;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终态收尾的事务边界守卫。
 * <p>
 * 改动前整个 {@code finalizeTask} 是一个 {@code @Transactional}：一个大视频几十秒的
 * 「HTTP 拉取 → 落临时文件 → 上传 OSS」<b>全程占着一条数据库连接什么也不干</b>，
 * MySQL 侧还挂着一条长事务。消费串行所以现在只占 1 条（池上限 50）——
 * 是随实例数与并发度线性恶化的隐患。
 * <p>
 * 但事务不能缩过头：落库 CAS 与 {@code settle} 必须仍在同一个事务里。
 */
class FinalizeTransactionScopeTest {

    private static final Long TASK_ID = 1224L;

    /** LambdaUpdateWrapper 要用 MyBatis-Plus 的 lambda 缓存；纯单测里没有 Spring，得手动初始化 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                VideoTask.class);
    }

    /** 模拟「当前是否在事务里」——TransactionTemplate 的回调内为 true，外面为 false */
    private final AtomicBoolean inTransaction = new AtomicBoolean(false);

    private VideoDownloadService downloadService;
    private WalletService walletService;
    private CostRecordService costRecordService;
    private TaskEtaService taskEtaService;
    private VideoTaskServiceImpl service;

    private Boolean inTxDuringDownload;
    private Boolean inTxDuringSettle;

    @BeforeEach
    void setUp() throws Exception {
        downloadService = mock(VideoDownloadService.class);
        walletService = mock(WalletService.class);
        costRecordService = mock(CostRecordService.class);
        taskEtaService = mock(TaskEtaService.class);

        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(inv -> {
            inTransaction.set(true);
            try {
                return ((TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class));
            } finally {
                inTransaction.set(false);
            }
        });

        service = spy(new VideoTaskServiceImpl(downloadService, costRecordService,
                mock(ApplicationEventPublisher.class), null, taskEtaService, null, null,
                walletService, mock(PricingService.class), template));

        VideoTask task = new VideoTask();
        task.setId(TASK_ID);
        task.setBizTaskId("tsk_910e6f83");
        task.setUserId(2L);
        task.setStatus("PROCESSING");
        task.setModel("minimax-h3-hd");
        task.setFreezeAmount(new BigDecimal("4.50"));
        org.mockito.Mockito.doReturn(task).when(service).getById(TASK_ID);
        org.mockito.Mockito.doReturn(true).when(service).update(any());

        inTxDuringDownload = null;
        inTxDuringSettle = null;
        when(downloadService.download(anyString(), anyString())).thenAnswer(inv -> {
            inTxDuringDownload = inTransaction.get();
            return artifact();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            inTxDuringSettle = inTransaction.get();
            return null;
        }).when(walletService).settle(any(), any(), anyLong());
    }

    private VideoDownloadService.DownloadedArtifact artifact() {
        return new VideoDownloadService.DownloadedArtifact("tsk_910e6f83.mp4",
                new org.example.seedancegenarate.service.ArtifactStorage.StoredArtifact(
                        "outputs/tsk_910e6f83/result.mp4", "video/mp4", 6785774L, "etag"));
    }

    @Test
    void downloadRunsOutsideTheTransaction() throws Exception {
        // 【测什么】下载 + 转存 OSS 执行时**没有活跃事务**
        // 【怎么算红】把 @Transactional 加回方法上（或把 download 挪进回调）——
        //          一条数据库连接会陪着几十秒的网络 IO 空等，MySQL 侧挂长事务；
        //          实例数一多就是连接池耗尽
        service.finalizeTask(TASK_ID, "http://node/view?filename=a.mp4");

        assertFalse(inTxDuringDownload, "下载不能在事务里");
    }

    @Test
    void settleRunsInsideTheTransaction() throws Exception {
        // 【测什么】结算仍在事务里（和落库 CAS 同一个）
        // 【怎么算红】事务缩过头，settle 跑到事务外 —— 落库成功但结算失败时不会一起回滚，
        //          任务显示成功却没扣钱，且对账要到第二天才发现
        service.finalizeTask(TASK_ID, "http://node/view?filename=a.mp4");

        assertTrue(inTxDuringSettle, "结算必须在事务里");
        verify(walletService).settle(eq(2L), eq(new BigDecimal("4.50")), eq(TASK_ID));
    }

    @Test
    void settleFailurePropagatesSoTheWholeTransactionRollsBack() throws Exception {
        // 【测什么】结算抛异常时异常往外冒 —— 事务回滚，作业重试可完整重放
        // 【怎么算红】把异常吞掉 —— 任务被置为 SUCCESS 但钱没结算，而且再也没人来补
        //          （因为状态已终态、CAS 不会再赢），钱永久错着
        doThrow(new IllegalStateException("余额异常")).when(walletService)
                .settle(any(), any(), anyLong());

        assertThrows(IllegalStateException.class,
                () -> service.finalizeTask(TASK_ID, "http://node/a.mp4"));
    }

    @Test
    void losingTheCasSkipsSettlementAndEtaRefresh() throws Exception {
        // 【测什么】CAS 抢输（别的 Worker 已落终态）时不结算、不刷缓存
        // 【怎么算红】抢输了还照样结算 —— 同一任务被结算两次；虽然 biz_key 幂等挡得住，
        //          但那是最后一道防线，不该靠它兜
        org.mockito.Mockito.doReturn(false).when(service).update(any());

        service.finalizeTask(TASK_ID, "http://node/a.mp4");

        verify(walletService, never()).settle(any(), any(), anyLong());
        verify(taskEtaService, never()).refreshAvgDuration(anyString());
    }

    @Test
    void etaRefreshFailureDoesNotFailTheTask() throws Exception {
        // 【测什么】ETA 缓存刷新失败只记日志，不影响已提交的终态
        // 【怎么算红】让它往外抛 —— 一次 Redis 抖动就把整笔已结算的成功任务变成作业失败，
        //          然后重试、重新下载整个视频
        doThrow(new RuntimeException("Redis 连接超时")).when(taskEtaService)
                .refreshAvgDuration(anyString());

        service.finalizeTask(TASK_ID, "http://node/a.mp4");

        verify(walletService).settle(any(), any(), anyLong());
    }

    @Test
    void alreadyTerminalTaskNeverDownloads() throws Exception {
        // 【测什么】任务已终态时直接跳过，连下载都不发起
        // 【怎么算红】先下载再判断 —— 重复投递的作业会把整个视频重新拉一遍才发现没用
        VideoTask done = new VideoTask();
        done.setId(TASK_ID);
        done.setStatus("SUCCESS");
        org.mockito.Mockito.doReturn(done).when(service).getById(TASK_ID);

        service.finalizeTask(TASK_ID, "http://node/a.mp4");

        verify(downloadService, never()).download(anyString(), anyString());
        assertEquals(null, inTxDuringDownload);
    }
}
