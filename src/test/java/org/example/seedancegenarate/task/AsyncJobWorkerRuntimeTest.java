package org.example.seedancegenarate.task;

import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.config.AsyncConfig;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.runtime.AgentStepHandler;
import org.example.seedancegenarate.agent.runtime.AgentSkillHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AsyncJobWorkerRuntimeTest {

    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(1);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdownPools() {
        dispatcher.shutdownNow();
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }

    @Test
    void fullPoolDoesNotClaimAndNextTypeEventuallyRuns() throws Exception {
        // 【测什么】只有一个执行槽时，慢作业占用期间不领第二张；槽位释放后轮到另一 job type。
        // 【怎么算红】先 claim 一批再串行，或热 type 不回队尾，会在慢作业未放行时 claim fast 或 fast 始终不执行。
        AsyncJobService jobs = mock(AsyncJobService.class);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastRan = new CountDownLatch(1);
        AsyncJob slowLease = lease(1L, "SLOW");
        AsyncJob fastLease = lease(2L, "FAST");
        AsyncJobHandler slow = handler("SLOW", 3, job -> {
            slowStarted.countDown();
            await(releaseSlow);
        });
        AsyncJobHandler fast = handler("FAST", 3, job -> fastRan.countDown());
        when(jobs.claimBatch("SLOW", 1, 3)).thenReturn(List.of(slowLease), List.of());
        when(jobs.claimBatch("FAST", 1, 3)).thenReturn(List.of(fastLease), List.of());
        AsyncJobWorkerRuntime runtime = runtime(jobs, List.of(slow, fast));

        runtime.wake("SLOW");
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
        runtime.wake("FAST");

        verify(jobs, after(150).never()).claimBatch("FAST", 1, 3);
        releaseSlow.countDown();
        assertTrue(fastRan.await(2, TimeUnit.SECONDS));
        verify(jobs, atLeastOnce()).claimBatch("FAST", 1, 3);
    }

    @Test
    void longExecutionRenewsLeaseButRuntimeNeverCompletesForHandler() throws Exception {
        // 【测什么】长任务在执行中周期续租；renew=false 后 runtime 也不伪造 complete，业务收口仍靠 handler 的 CAS/fencing。
        // 【怎么算红】不安排 heartbeat，或 handler 返回后 runtime 自动 complete，这条必须变红。
        AsyncJobService jobs = mock(AsyncJobService.class);
        AsyncJob lease = lease(3L, "LONG");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jobs.claimBatch("LONG", 1, 1)).thenReturn(List.of(lease), List.of());
        when(jobs.renew(lease, 1)).thenReturn(false);
        AsyncJobHandler handler = handler("LONG", 1, job -> {
            started.countDown();
            await(release);
        });
        AsyncJobWorkerRuntime runtime = runtime(jobs, List.of(handler));

        runtime.wake("LONG");
        assertTrue(started.await(2, TimeUnit.SECONDS));
        verify(jobs, after(800).atLeastOnce()).renew(lease, 1);
        release.countDown();

        verify(jobs, after(300).never()).complete(lease);
    }

    @Test
    void productionBoundedPoolHandsOffTwoConsecutiveJobsWithoutStrandingLease() throws Exception {
        // 【测什么】单线程 handler 在 finally 释放 permit、Runnable 尚未返回的窗口，第二张已领 job 能进交接槽。
        // 【怎么算红】生产 worker queueCapacity=0 时第二次 execute 会被拒，job 白持租约到过期。
        AsyncJobService jobs = mock(AsyncJobService.class);
        AsyncJob first = lease(11L, "ONE");
        AsyncJob second = lease(12L, "ONE");
        when(jobs.claimBatch("ONE", 1, 3))
                .thenReturn(List.of(first), List.of(second), List.of());
        CountDownLatch ran = new CountDownLatch(2);
        AsyncJobHandler handler = handler("ONE", 3, ignored -> ran.countDown());
        ThreadPoolTaskExecutor productionWorkers = (ThreadPoolTaskExecutor)
                new AsyncConfig().asyncJobWorkerExecutor(1);
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setWorkerThreads(1);
        properties.setReconcileIntervalMs(30_000);
        properties.setReconcileJitterPercent(20);
        AsyncJobWorkerRuntime runtime = new AsyncJobWorkerRuntime(
                jobs, List.of(handler), properties, dispatcher, productionWorkers, heartbeats);
        try {
            runtime.wake("ONE");

            assertTrue(ran.await(2, TimeUnit.SECONDS), "连续两张 job 都应执行，不能把第二张滞留到租约过期");
            verify(jobs, atLeast(2)).claimBatch("ONE", 1, 3);
        } finally {
            productionWorkers.shutdown();
        }
    }

    private AsyncJobWorkerRuntime runtime(AsyncJobService jobs, List<AsyncJobHandler> handlers) {
        AsyncJobProperties properties = new AsyncJobProperties();
        properties.setWorkerThreads(1);
        properties.setReconcileIntervalMs(30_000);
        properties.setReconcileJitterPercent(20);
        return new AsyncJobWorkerRuntime(jobs, handlers, properties, dispatcher, workers, heartbeats);
    }

    @Test
    void agentTypesShareTwoPermitsAndGenerationKeepsOneSlot() throws Exception {
        // 【测什么】Agent决策/Skill共用两槽，满时不领第三张；普通生成可并行，释放后组内另一类型恢复。
        // 【怎么算红】移除claim前共享组permit或把两种Agent分组，第三张会提前被领取。
        verifyAgentCapacity(3, 2);
    }

    @Test
    void twoThreadWorkerReservesOneSlotForGeneration() throws Exception {
        // 【测什么】worker只有两线程时Agent组上限降为1，仍给生成留一槽。
        // 【怎么算红】始终给Agent组两槽而不按workerThreads-1裁剪时Skill被提前领取。
        verifyAgentCapacity(2, 1);
    }

    private void verifyAgentCapacity(int threadCount, int agentLimit) throws Exception {
        AsyncJobService jobs = mock(AsyncJobService.class);
        AgentRuntime agent = mock(AgentRuntime.class);
        ExecutorService sharedWorkers = Executors.newFixedThreadPool(threadCount);
        CountDownLatch beginDispatch = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(agentLimit);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch generationRan = new CountDownLatch(1);
        CountDownLatch allAgentRan = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(call -> {
            int count = active.incrementAndGet(); maxActive.accumulateAndGet(count, Math::max);
            started.countDown(); allAgentRan.countDown();
            try { await(release); } finally { active.decrementAndGet(); }
            return null;
        }).when(agent).execute(any(AsyncJob.class), anyBoolean());
        var step = new AgentStepHandler(agent);
        var skill = new AgentSkillHandler(agent);
        when(jobs.claimBatch(step.jobType(), 1, step.leaseSeconds()))
                .thenReturn(List.of(lease(21L, step.jobType())), List.of(lease(23L, step.jobType())), List.of());
        when(jobs.claimBatch(skill.jobType(), 1, skill.leaseSeconds()))
                .thenReturn(List.of(lease(22L, skill.jobType())), List.of());
        when(jobs.claimBatch("GENERATION", 1, 3)).thenReturn(List.of(lease(24L, "GENERATION")), List.of());
        AsyncJobProperties properties = new AsyncJobProperties(); properties.setWorkerThreads(threadCount);
        properties.setReconcileIntervalMs(30_000); properties.setReconcileJitterPercent(20);
        var runtime = new AsyncJobWorkerRuntime(jobs, List.of(step, skill,
                handler("GENERATION", 3, ignored -> generationRan.countDown())), properties,
                action -> dispatcher.execute(() -> { try { await(beginDispatch); action.run(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); } }), sharedWorkers, heartbeats);
        try {
            runtime.wake(step.jobType()); runtime.wake(skill.jobType()); runtime.wake("GENERATION");
            beginDispatch.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(generationRan.await(2, TimeUnit.SECONDS), "普通生成不能等长LLM释放槽");
            verify(jobs, after(150).times(1)).claimBatch(step.jobType(), 1, step.leaseSeconds());
            verify(jobs, after(150).times(agentLimit - 1)).claimBatch(skill.jobType(), 1, skill.leaseSeconds());
            assertEquals(agentLimit, maxActive.get());
            release.countDown();
            assertTrue(allAgentRan.await(2, TimeUnit.SECONDS), "释放后必须唤醒组内两个类型，不能等30s扫描");
            assertTrue(maxActive.get() <= agentLimit);
        } finally {
            release.countDown(); beginDispatch.countDown(); sharedWorkers.shutdownNow();
        }
    }

    private AsyncJobHandler handler(String type, long leaseSeconds, ThrowingConsumer action) {
        return new AsyncJobHandler() {
            @Override
            public String jobType() {
                return type;
            }

            @Override
            public long leaseSeconds() {
                return leaseSeconds;
            }

            @Override
            public void execute(AsyncJob lease) throws Exception {
                action.accept(lease);
            }
        };
    }

    private AsyncJob lease(Long id, String type) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setJobType(type);
        job.setStatus(AsyncJob.STATUS_RUNNING);
        job.setLeaseToken("lease-" + id);
        job.setLeaseGeneration(1L);
        return job;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        latch.await(3, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(AsyncJob job) throws Exception;
    }
}
