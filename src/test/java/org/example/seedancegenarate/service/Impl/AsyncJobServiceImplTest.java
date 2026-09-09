package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.example.seedancegenarate.service.JobAvailableNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AsyncJobServiceImplTest {

    @Test
    // 【测什么】新作业实际入队后才发布唤醒通知。
    // 【怎么算红】删掉 enqueue 成功后的 notifier.notify 调用，这条必须变红。
    void enqueueUpsertsAndNotifiesConsumersWhenActuallyInserted() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.selectLastUpsertId()).thenReturn(21L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueue("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", "{}");

        verify(mapper).upsertReady("PIPELINE_NODE_SUBMIT", "pipeline:1:node:2", "{}", 5);
        verify(notifier).notify("PIPELINE_NODE_SUBMIT");
    }

    @Test
    // 【测什么】事务内入队只能在 commit 后通知，避免 Worker 先醒却看不到未提交 job。
    // 【怎么算红】恢复 enqueue 内立即 notify，commit 前 verifyNoInteractions 会失败。
    void enqueueDefersNotificationUntilTransactionCommit() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.selectLastUpsertId()).thenReturn(7L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.enqueue("GENERATION_SUBMIT", "attempt:7", "{}");

            verifyNoInteractions(notifier);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(notifier).notify("GENERATION_SUBMIT");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    // 【测什么】活跃作业重复入队不应刷 Redis 通知。
    // 【怎么算红】让 enqueue 无视 mapper 影响行数始终通知，这条必须变红。
    void doesNotNotifyWhenJobAlreadyActive() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        // 活跃 duplicate 由 LAST_INSERT_ID(0) 发回 no-op 信号：不通知，避免重复 poll 刷频道
        when(mapper.selectLastUpsertId()).thenReturn(0L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueue("TASK_FINALIZE", "task:10", "{}");

        org.mockito.Mockito.verifyNoInteractions(notifier);
    }

    @Test
    void terminalDuplicateIsRestartedAndNotified() {
        // 【测什么】单条 UPSERT 命中终态旧行后会重开且只发一次通知。
        // 【怎么算红】终态不再返回正 id 时，周期 TASK_POLL/ORDER_CLOSE 会静默重开但不及时唤醒 Worker。
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.selectLastUpsertId()).thenReturn(10L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueue("TASK_POLL", "task:10:attempt:7", "{}");

        verify(mapper).upsertReady("TASK_POLL", "task:10:attempt:7", "{}", 5);
        verify(notifier).notify("TASK_POLL");
    }

    @Test
    void enqueueInputsAreValidatedBeforeSql() {
        // 【测什么】UPSERT 前限制真实列/内存边界，不能让超长输入进入数据库。
        // 【怎么算红】删除校验会让超长 key/payload 被截断并可能碰撞另一张作业。
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(
                mapper, properties(), mock(JobAvailableNotifier.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("T".repeat(65), "key", "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("TYPE", "k".repeat(192), "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("TYPE", "key", "界".repeat(11_000)));
        assertThrows(IllegalArgumentException.class,
                () -> service.enqueueDelayed("TYPE", "key", "{}", 86_401));

        verifyNoInteractions(mapper);
    }

    @Test
    // 【测什么】每批给过期 RUNNING 保留 1/4 名额，其余给 READY，且只返回 CAS 赢家。
    // 【怎么算红】把过期份额改成 0/全批，或把 claim 影响 0 行的作业也返回，这条必须变红。
    void claimReservesBoundedExpiredShareAndReturnsOnlyCasWinners() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob expired = candidate(10L, AsyncJob.STATUS_RUNNING, 4L);
        AsyncJob readyWinner = candidate(20L, AsyncJob.STATUS_READY, 0L);
        AsyncJob readyLoser = candidate(30L, AsyncJob.STATUS_READY, 2L);
        when(mapper.selectExpiredForClaim("PIPELINE_NODE_SUBMIT", 2)).thenReturn(List.of(expired));
        when(mapper.selectReadyForClaim("PIPELINE_NODE_SUBMIT", 7)).thenReturn(List.of(readyWinner, readyLoser));
        when(mapper.claim(eq(10L), any(), any(), eq(60L))).thenReturn(1);
        when(mapper.claim(eq(20L), any(), any(), eq(60L))).thenReturn(1);
        when(mapper.claim(eq(30L), any(), any(), eq(60L))).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        List<AsyncJob> claimed = service.claimBatch("PIPELINE_NODE_SUBMIT", 8, 60);

        assertEquals(2, claimed.size());
        assertEquals(10L, claimed.get(0).getId());
        assertNotNull(claimed.get(0).getLeaseToken());
        assertEquals(5L, claimed.get(0).getLeaseGeneration());
        assertEquals(20L, claimed.get(1).getId());
        assertEquals(1L, claimed.get(1).getLeaseGeneration());
    }

    @Test
    // 【测什么】没有过期作业时 READY 可以用满整批，不浪费空额。
    // 【怎么算红】始终只按 3/4 查 READY 而不回填空缺的过期份额，这条必须变红。
    void claimGivesUnusedExpiredShareBackToReadyJobs() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        when(mapper.selectExpiredForClaim("TASK_FINALIZE", 2)).thenReturn(List.of());
        when(mapper.selectReadyForClaim("TASK_FINALIZE", 8)).thenReturn(List.of());
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        service.claimBatch("TASK_FINALIZE", 8, 300);

        verify(mapper).selectReadyForClaim("TASK_FINALIZE", 8);
    }

    @Test
    void singleSlotAlternatesExpiredAndReadyPerJobType() {
        // 【测什么】runtime 固定 batch=1 时，同一 type 的过期接管与 READY 交替，且每个 type 独立记偏好。
        // 【怎么算红】永远先查 expired 会让持续故障的过期 RUNNING 永久饿死新 READY；全局 toggle 会让不同 type 偏食。
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob aExpired = candidate(1L, AsyncJob.STATUS_RUNNING, 1L);
        AsyncJob aReady = candidate(2L, AsyncJob.STATUS_READY, 0L);
        AsyncJob bExpired = candidate(3L, AsyncJob.STATUS_RUNNING, 1L);
        when(mapper.selectExpiredForClaim("A", 1)).thenReturn(List.of(aExpired));
        when(mapper.selectReadyForClaim("A", 1)).thenReturn(List.of(aReady));
        when(mapper.selectExpiredForClaim("B", 1)).thenReturn(List.of(bExpired));
        when(mapper.claim(any(), any(), any(), eq(60L))).thenReturn(1);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(
                mapper, properties(), mock(JobAvailableNotifier.class));

        List<AsyncJob> firstA = service.claimBatch("A", 1, 60);
        List<AsyncJob> secondA = service.claimBatch("A", 1, 60);
        List<AsyncJob> firstB = service.claimBatch("B", 1, 60);

        assertEquals(1L, firstA.get(0).getId());
        assertEquals(2L, secondA.get(0).getId());
        assertEquals(3L, firstB.get(0).getId(), "B 必须从自己的 expired-first 开始");
    }

    @Test
    // 【测什么】同一服务实例的 lease owner 在多次领取中保持稳定。
    // 【怎么算红】恢复为每次 claim 都生成新 owner UUID，这条必须变红。
    void leaseOwnerIsStableForTheServiceLifetime() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        when(mapper.selectExpiredForClaim("ORDER_CLOSE", 1)).thenReturn(List.of());
        when(mapper.selectReadyForClaim("ORDER_CLOSE", 2))
                .thenReturn(List.of(candidate(10L, AsyncJob.STATUS_READY, 0L)))
                .thenReturn(List.of(candidate(20L, AsyncJob.STATUS_READY, 0L)));
        when(mapper.claim(any(), any(), any(), eq(60L))).thenReturn(1);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        service.claimBatch("ORDER_CLOSE", 2, 60);
        service.claimBatch("ORDER_CLOSE", 2, 60);

        org.mockito.ArgumentCaptor<String> owner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper, org.mockito.Mockito.times(2)).claim(any(), owner.capture(), any(), eq(60L));
        assertEquals(owner.getAllValues().get(0), owner.getAllValues().get(1));
    }

    @Test
    // 【测什么】续租必须携带 id、token、generation，mapper 返回 0 即表示租约已丢失。
    // 【怎么算红】续租忽略 generation 或将 0 行当成成功，这条必须变红。
    void renewUsesTokenAndGenerationAsFence() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob lease = claimed(10L, "new-token", 3L);
        when(mapper.renew(10L, "new-token", 3L, 60L)).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        assertFalse(service.renew(lease, 60));
        verify(mapper).renew(10L, "new-token", 3L, 60L);
    }

    @Test
    // 【测什么】旧 Worker 的 token/generation 无法完成已被新 Worker 接管的租约。
    // 【怎么算红】complete SQL 只按 id 或 token 更新，或 service 忽略 0 行，这条必须变红。
    void staleGenerationCannotCompleteNewLease() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob staleLease = claimed(10L, "old-token", 2L);
        when(mapper.complete(10L, "old-token", 2L)).thenReturn(0);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        assertFalse(service.complete(staleLease));
        verify(mapper).complete(10L, "old-token", 2L);
    }

    @Test
    // 【测什么】失败记录不先读作业，而是由一条 fenced UPDATE 原子加次数并排退避。
    // 【怎么算红】恢复 selectById+内存判定，或少传 generation，这条必须变红。
    void failAndRetryIsOneFencedUpdate() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob job = claimed(10L, "token", 6L);
        job.setAttempts(4);
        job.setMaxAttempts(5);
        when(mapper.failAndRetry(10L, "token", 6L, 480L, "boom")).thenReturn(1);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        assertTrue(service.failAndRetry(job, "boom"));

        verify(mapper).failAndRetry(10L, "token", 6L, 480L, "boom");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).selectById(any());
    }

    @Test
    // 【测什么】非法批量或租期在到达 SQL 前被拒绝，不生成立即过期或过大租约。
    // 【怎么算红】把 requireBatchSize/requireLeaseSeconds 换成 Math.max 静默夹值，这条必须变红。
    void invalidClaimBoundsFailBeforeAnySql() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), mock(JobAvailableNotifier.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.claimBatch("TASK_FINALIZE", 0, 60));
        assertThrows(IllegalArgumentException.class,
                () -> service.claimBatch("TASK_FINALIZE", 101, 60));
        assertThrows(IllegalArgumentException.class,
                () -> service.claimBatch("TASK_FINALIZE", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.claimBatch("TASK_FINALIZE", 1, 86_401));
        verifyNoInteractions(mapper);
    }

    @Test
    // 【测什么】延迟作业按请求秒数入队，并在到期时安排二次 doorbell。
    // 【怎么算红】只在入队时立即 notify，作业当时不可领取，随后会退化到 30 秒扫描；本断言必须变红。
    void enqueueDelayedPassesDelaySecondsAndSchedulesDueDoorbell() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.selectLastUpsertId()).thenReturn(31L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{\"orderNo\":\"ALP1\"}", 600);

        verify(mapper).upsertReadyDelayed("ORDER_CLOSE", "order:ALP1", "{\"orderNo\":\"ALP1\"}", 5, 600);
        verify(notifier).notifyAfterDelay("ORDER_CLOSE", 600);
    }

    @Test
    // 【测什么】活跃的延迟作业重复入队不刷通知。
    // 【怎么算红】忽略 mapper 的 0 行结果并发布通知，这条必须变红。
    void enqueueDelayedDoesNotNotifyWhenJobAlreadyActive() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobAvailableNotifier notifier = mock(JobAvailableNotifier.class);
        when(mapper.selectLastUpsertId()).thenReturn(0L);
        AsyncJobServiceImpl service = new AsyncJobServiceImpl(mapper, properties(), notifier);

        service.enqueueDelayed("ORDER_CLOSE", "order:ALP1", "{}", 600);

        org.mockito.Mockito.verifyNoInteractions(notifier);
    }

    private AsyncJob candidate(Long id, String status, Long generation) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType("PIPELINE_NODE_SUBMIT");
        job.setBizKey("pipeline:1:node:" + id);
        job.setPayload("{}");
        job.setStatus(status);
        job.setAttempts(0);
        job.setMaxAttempts(5);
        job.setLeaseGeneration(generation);
        return job;
    }

    private AsyncJob claimed(Long id, String token, Long generation) {
        AsyncJob job = candidate(id, AsyncJob.STATUS_RUNNING, generation);
        job.setLeaseToken(token);
        return job;
    }

    private AsyncJobProperties properties() {
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setMaxAttempts(5);
        properties.setBackoffBaseSeconds(30);
        return properties;
    }
}
