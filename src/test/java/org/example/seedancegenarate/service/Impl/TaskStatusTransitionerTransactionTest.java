package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class TaskStatusTransitionerTransactionTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                VideoTask.class);
    }

    @Test
    void rollbackNeverReleasesRedisAdmissionSlot() {
        // 【测什么】外层终态事务回滚时，Redis admission 槽绝不能提前释放。
        // 【怎么算红】把 releaseQuietly 放回 task CAS 后立即调用，会在 rollback 前产生一次释放。
        Fixture fixture = fixture();
        beginSynchronization();
        try {
            assertTrue(fixture.transitioner.markFailedIfCurrent(fixture.task, "failed"));
            verifyNoInteractions(fixture.admission);
            verifyNoInteractions(fixture.walletRelease);

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verifyNoInteractions(fixture.admission);
            verifyNoInteractions(fixture.walletRelease);
        } finally {
            endSynchronization();
        }
    }

    @Test
    void commitReleasesAdmissionExactlyOnceAfterCommit() {
        // 【测什么】CAS 赢家只在 MySQL commit 后释放一次槽位。
        // 【怎么算红】未注册 afterCommit 会一直不释放；同时立即+afterCommit 会释放两次。
        Fixture fixture = fixture();
        beginSynchronization();
        try {
            assertTrue(fixture.transitioner.markFailedIfCurrent(fixture.task, "failed"));
            verifyNoInteractions(fixture.admission);
            verifyNoInteractions(fixture.walletRelease);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(fixture.admission, times(1)).releaseQuietly(2L, 42L, null);
            verify(fixture.walletRelease, times(1)).release(fixture.task);
        } finally {
            endSynchronization();
        }
    }

    @Test
    void walletReleaseFailureAfterCommitCannotRollbackTerminalTransaction() {
        // 【测什么】失败解冻直到 afterCommit 才调用，且其异常被补偿语义吸收。
        // 【怎么算红】在 REQUIRED outer tx 内调用 release 时，代理会把事务标 rollback-only，job 永久接管。
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("wallet unavailable")).when(fixture.walletRelease)
                .release(fixture.task);
        beginSynchronization();
        try {
            assertTrue(fixture.transitioner.markFailedIfCurrent(fixture.task, "failed"));
            verifyNoInteractions(fixture.walletRelease);

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(synchronization::afterCommit);
            }

            verify(fixture.walletRelease).release(fixture.task);
        } finally {
            endSynchronization();
        }
    }

    @Test
    void failureWalletBoundaryAlwaysStartsANewTransaction() throws Exception {
        // 【测什么】afterCommit 钱包写通过独立 bean 的 REQUIRES_NEW 边界重新提交。
        // 【怎么算红】改回 REQUIRED 会加入已提交但尚未解绑的旧资源，写入不会再获得一次 commit。
        Transactional transactional = org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(FailureWalletReleaseService.class
                        .getMethod("release", VideoTask.class), Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void staleFailedSnapshotCannotTerminateReplacementAttempt() {
        // 【测什么】poll 得到旧 FAILED 后新 attempt 已激活，最终 SQL 前的 identity 栅栏拒绝旧结果。
        // 【怎么算红】只按 taskId/status 更新会调用 mapper.update 并终结新轮次。
        VideoTaskMapper mapper = mock(VideoTaskMapper.class);
        AdmissionControl admission = mock(AdmissionControl.class);
        VideoTask old = task(7L, "remote-7");
        VideoTask current = task(8L, "remote-8");
        when(mapper.selectById(42L)).thenReturn(current);
        TaskStatusTransitionerImpl transitioner = new TaskStatusTransitionerImpl(
                mapper, mock(ApplicationEventPublisher.class), admission,
                mock(FailureWalletReleaseService.class));

        org.junit.jupiter.api.Assertions.assertFalse(
                transitioner.markFailedIfCurrent(old, "late failure"));

        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(admission);
    }

    private Fixture fixture() {
        VideoTaskMapper mapper = mock(VideoTaskMapper.class);
        AdmissionControl admission = mock(AdmissionControl.class);
        FailureWalletReleaseService walletRelease = mock(FailureWalletReleaseService.class);
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setBizTaskId("tsk_42");
        task.setUserId(2L);
        task.setStatus("PROCESSING");
        task.setPhase("FINALIZING");
        task.setProvider("seedance");
        task.setProviderTaskId("remote-7");
        task.setCurrentAttemptId(7L);
        task.setRetryCount(0);
        task.setFreezeAmount(BigDecimal.ONE);
        when(mapper.selectById(42L)).thenReturn(task);
        when(mapper.update(any(), any())).thenReturn(1);
        TaskStatusTransitionerImpl transitioner = new TaskStatusTransitionerImpl(
                mapper, mock(ApplicationEventPublisher.class), admission, walletRelease);
        return new Fixture(transitioner, admission, walletRelease, task);
    }

    private VideoTask task(Long attemptId, String providerTaskId) {
        VideoTask task = new VideoTask();
        task.setId(42L);
        task.setStatus("PROCESSING");
        task.setPhase("RUNNING");
        task.setProvider("seedance");
        task.setProviderTaskId(providerTaskId);
        task.setCurrentAttemptId(attemptId);
        task.setRetryCount(0);
        return task;
    }

    private void beginSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void endSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private record Fixture(TaskStatusTransitionerImpl transitioner,
                           AdmissionControl admission, FailureWalletReleaseService walletRelease,
                           VideoTask task) {
    }
}
