package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.task.ApiKeyBudgetReconcileTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Independent audit guards; compose the shared fixture without inheriting its tests.
 * Run serially with BillingAuthorizationIntegrationTest: its optional MySQL schema is shared.
 * H2 runs scanner guards only; it must not be presented as evidence of MySQL RR semantics.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ApiKeyBudgetAuditTest {
    private BillingAuthorizationIntegrationTest fixture;

    @BeforeEach
    void setup() {
        fixture = new BillingAuthorizationIntegrationTest();
        fixture.setup();
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) fixture.cleanup();
    }

    // 【测什么】MySQL RR旧读视图看不到新授权时，成功收尾仍当前读并同时结算预算和钱包。
    // 【怎么算红】finish恢复普通快照读会留下RESERVED；钱包误认未冻结则余额二次扣款/冻结残留。
    @Test
    void settlementSeesAuthorizationCommittedAfterRepeatableReadSnapshot() throws Exception {
        finishAfterOldSnapshot("SUCCESS");
    }

    // 【测什么】MySQL RR旧读视图下失败收尾能看见新授权及FREEZE，不能仅关闭预算却不退款。
    // 【怎么算红】预算或钱包使用旧快照判断不存在，将留下预占或wallet.frozen=10。
    @Test
    void releaseSeesAuthorizationCommittedAfterRepeatableReadSnapshot() throws Exception {
        finishAfterOldSnapshot("FAILED");
    }

    private void finishAfterOldSnapshot(String terminal) throws Exception {
        assumeTrue(System.getProperty("budget.test.jdbcUrl", "").startsWith("jdbc:mysql:"),
                "Requires the fixture's allowlisted isolated MySQL; H2 is not RR evidence");
        VideoTask task = task(101, "10");
        var rr = new TransactionTemplate(fixture.first.getBean(
                org.springframework.transaction.PlatformTransactionManager.class));
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var writer = Executors.newSingleThreadExecutor();
        try {
            rr.executeWithoutResult(status -> {
                // Establish an old consistent read view before B reserves. Do not lock task/key here.
                assertEquals(0, count("SELECT COUNT(*) FROM api_key_budget_authorization"));
                assertEquals(0, count("SELECT COUNT(*) FROM balance_transaction"));
                try {
                    // A separate thread is essential: Spring transactions are thread-bound even
                    // when services come from different application contexts.
                    writer.submit(() -> fixture.b.freeze(task, task.getFreezeAmount()))
                            .get(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted waiting for independent reserve", e);
                } catch (Exception e) {
                    throw new AssertionError("Independent reserve must commit before terminal", e);
                }
                // Prove this is really a stale snapshot, rather than a sequential happy path.
                assertEquals(0, count("SELECT COUNT(*) FROM api_key_budget_authorization"));
                assertEquals(0, count("SELECT COUNT(*) FROM balance_transaction"));
                assertEquals(1, fixture.db.update("UPDATE video_task SET status=? "
                        + "WHERE id=? AND status='PROCESSING'", terminal, task.getId()));
                task.setStatus(terminal);
                finish(task);
                // Inspect current state inside A, before any scheduled compensation could repair it.
                assertEquals(expectedState(terminal), fixture.db.queryForObject(
                        "SELECT state FROM api_key_budget_authorization WHERE task_id=101 FOR UPDATE",
                        String.class));
                money("SELECT frozen FROM wallet WHERE user_id=7 FOR UPDATE", "0");
                money("SELECT balance FROM wallet WHERE user_id=7 FOR UPDATE",
                        "SUCCESS".equals(terminal) ? "90" : "100");
            });
        } finally {
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(20, TimeUnit.SECONDS), "Writer must stop before fixture cleanup");
        }
        assertEquals(expectedState(terminal), state(101));
        money("SELECT SUM(reserved) FROM api_key_budget_period", "0");
        money("SELECT SUM(consumed) FROM api_key_budget_period", "SUCCESS".equals(terminal) ? "10" : "0");
        assertEquals(2, count("SELECT COUNT(*) FROM balance_transaction WHERE task_id=101"));
        assertEquals(1, count("SELECT COUNT(*) FROM api_key_budget_ledger WHERE task_id=101 AND phase='FINISH'"));
    }

    // 【测什么】错误金额RELEASE已存在时保持预算可补偿，后继正常任务仍完成且退出扫描。
    // 【怎么算红】移除既有钱包流水金额校验，毒行将变RELEASED并从findReserved消失。
    @Test
    void wrongReleaseAmountRemainsScannableWithoutBlockingHealthyTask() {
        wrongExistingTerminalRemainsScannable("FAILED");
    }

    // 【测什么】错误金额SETTLE已存在不能被预算补偿认作完整结算，也不能饿死后继任务。
    // 【怎么算红】只按bizKey幂等跳过会将错误10元授权结清，reserved由10变0。
    @Test
    void wrongSettlementAmountRemainsScannableWithoutBlockingHealthyTask() {
        wrongExistingTerminalRemainsScannable("SUCCESS");
    }

    private void wrongExistingTerminalRemainsScannable(String terminal) {
        VideoTask poison = task(101, "10");
        VideoTask healthy = task(102, "3");
        fixture.a.freeze(poison, poison.getFreezeAmount());
        fixture.a.freeze(healthy, healthy.getFreezeAmount());
        terminal(poison, terminal);
        terminal(healthy, terminal);
        // Construct the audited partial legacy-wallet state using real ledger/wallet writes:
        // FREEZE10, terminal6, residual frozen4. No mock manufactures accounting success.
        walletFinish(poison, new BigDecimal("6"));
        ApiKeyBudgetReconcileTask scanner = scanner(poison, healthy);
        scanner.reconcile();
        assertEquals("RESERVED", state(101), "Anomalous ledger must not disappear from compensation");
        assertEquals(expectedState(terminal), state(102), "Poison row must not block its successor");
        assertEquals(List.of(101L), reservedIds());
        money("SELECT SUM(reserved) FROM api_key_budget_period", "10");
        money("SELECT SUM(consumed) FROM api_key_budget_period", "SUCCESS".equals(terminal) ? "3" : "0");
        money("SELECT frozen FROM wallet WHERE user_id=7", "4");
        money("SELECT balance FROM wallet WHERE user_id=7", "SUCCESS".equals(terminal) ? "87" : "96");
        assertEquals(0, count("SELECT COUNT(*) FROM api_key_budget_ledger WHERE task_id=101 AND phase='FINISH'"));
        money("SELECT hold_amount FROM balance_transaction WHERE task_id=101 AND type='"
                + ("SUCCESS".equals(terminal) ? "SETTLE" : "RELEASE") + "'", "6");
        scanner.reconcile(); // cursor reaches the end and resets
        scanner.reconcile(); // revisits the anomalous authorization, still refusing to close it
        assertEquals(List.of(101L), reservedIds());
        assertEquals(1, count("SELECT COUNT(*) FROM api_key_budget_ledger WHERE phase='FINISH'"));
        assertEquals(4, count("SELECT COUNT(*) FROM balance_transaction"));
    }

    // 【测什么】匹配金额的既有RELEASE允许单独补齐预算，退出扫描后重复扫描不再次退款。
    // 【怎么算红】补偿仅查缺钱包流水或无条件拒绝已有流水，会使预算永远RESERVED。
    @Test
    void matchingReleaseExitsScanWithoutRefundingTwice() {
        matchingExistingTerminalExitsScan("FAILED");
    }

    // 【测什么】匹配金额的既有SETTLE允许补齐预算消费且只结算一次。
    // 【怎么算红】跳过钱包齐全任务会残留预占，重复消费则consumed超过10。
    @Test
    void matchingSettlementExitsScanWithoutChargingTwice() {
        matchingExistingTerminalExitsScan("SUCCESS");
    }

    private void matchingExistingTerminalExitsScan(String terminal) {
        VideoTask task = task(101, "10");
        fixture.a.freeze(task, task.getFreezeAmount());
        terminal(task, terminal);
        walletFinish(task, task.getFreezeAmount());
        assertEquals(List.of(101L), reservedIds(), "Wallet completion must not hide an unfinished budget");
        ApiKeyBudgetReconcileTask scanner = scanner(task);
        scanner.reconcile();
        scanner.reconcile();
        scanner.reconcile();
        assertEquals(expectedState(terminal), state(101));
        assertEquals(List.of(), reservedIds());
        money("SELECT SUM(reserved) FROM api_key_budget_period", "0");
        money("SELECT SUM(consumed) FROM api_key_budget_period", "SUCCESS".equals(terminal) ? "10" : "0");
        money("SELECT frozen FROM wallet WHERE user_id=7", "0");
        money("SELECT balance FROM wallet WHERE user_id=7", "SUCCESS".equals(terminal) ? "90" : "100");
        assertEquals(2, count("SELECT COUNT(*) FROM balance_transaction"));
        assertEquals(1, count("SELECT COUNT(*) FROM api_key_budget_ledger WHERE phase='FINISH'"));
    }

    // 【测什么】不同Key同账号、task:101与task:1010首次失败收尾不能因缺失终态键共享gap而死锁。
    // 【怎么算红】三键FOR UPDATE核验位于钱包插入前时，两事务先持有相同gap，再插RELEASE互等；
    // 修复后核验移至钱包写入之后，屏障仍在钱包入口，不再强制任何缺失键锁先于插入。
    @Test
    void differentKeysWithPrefixTaskIdsCanReleaseConcurrently() throws Exception {
        assumeTrue(System.getProperty("budget.test.jdbcUrl", "").startsWith("jdbc:mysql:"),
                "Requires the fixture's isolated MySQL: H2 does not reproduce InnoDB gap locks");
        VideoTask left = task(101, "10");
        VideoTask right = task(1010, "10");
        right.setApiKeyId(2L);
        assertEquals(1, fixture.db.update("UPDATE video_task SET api_key_id=2 WHERE id=1010"));
        fixture.a.freeze(left, left.getFreezeAmount());
        fixture.b.freeze(right, right.getFreezeAmount());
        terminal(left, "FAILED");
        terminal(right, "FAILED");
        assertEquals(List.of("task:101", "task:1010"), fixture.db.queryForList(
                "SELECT biz_key FROM balance_transaction ORDER BY biz_key", String.class));

        WalletService realWallet = fixture.first.getBean(WalletService.class);
        WalletService gatedWallet = mock(WalletService.class);
        CyclicBarrier beforeWallet = new CyclicBarrier(2);
        doAnswer(call -> {
            // Current facade has already run its missing-key locking verification here.
            // Both transactions must arrive before either inserts a terminal wallet ledger.
            beforeWallet.await(8, TimeUnit.SECONDS);
            realWallet.release(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(gatedWallet).release(anyLong(), any(BigDecimal.class), anyLong());
        BillingAuthorizationService billing = new BillingAuthorizationService(
                fixture.db, fixture.budgets, gatedWallet);
        TransactionTemplate rr = new TransactionTemplate(fixture.first.getBean(
                org.springframework.transaction.PlatformTransactionManager.class));
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var pool = Executors.newFixedThreadPool(2);
        try {
            // Explicit caller transactions wrap the real facade body; no annotation/self-call
            // assumption, and the real wallet/budget Spring proxies join these transactions.
            var a = pool.submit(() -> rr.executeWithoutResult(s -> billing.release(left, left.getFreezeAmount())));
            var b = pool.submit(() -> rr.executeWithoutResult(s -> billing.release(right, right.getFreezeAmount())));
            assertAll("Both valid refunds must commit; a deadlock is not a business rejection",
                    () -> assertDoesNotThrow(() -> a.get(20, TimeUnit.SECONDS)),
                    () -> assertDoesNotThrow(() -> b.get(20, TimeUnit.SECONDS)));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "Refund workers must stop before cleanup");
        }
        assertEquals("RELEASED", state(101));
        assertEquals("RELEASED", state(1010));
        money("SELECT balance FROM wallet WHERE user_id=7", "100");
        money("SELECT frozen FROM wallet WHERE user_id=7", "0");
        money("SELECT SUM(reserved) FROM api_key_budget_period", "0");
        assertEquals(2, count("SELECT COUNT(*) FROM balance_transaction WHERE type='RELEASE'"));
        assertEquals(2, count("SELECT COUNT(*) FROM api_key_budget_ledger WHERE phase='FINISH'"));
    }

    private VideoTask task(long id, String amount) {
        VideoTask task = new VideoTask();
        task.setId(id); task.setUserId(7L); task.setApiKeyId(1L);
        task.setStatus("PROCESSING"); task.setFreezeAmount(new BigDecimal(amount));
        fixture.db.update("INSERT INTO video_task(id,user_id,api_key_id,status,freeze_amount) "
                + "VALUES(?,7,1,'PROCESSING',?)", id, task.getFreezeAmount());
        return task;
    }

    private void terminal(VideoTask task, String status) {
        assertEquals(1, fixture.db.update("UPDATE video_task SET status=? WHERE id=?", status, task.getId()));
        task.setStatus(status);
    }

    private void finish(VideoTask task) {
        if ("SUCCESS".equals(task.getStatus())) fixture.a.settle(task, task.getFreezeAmount());
        else fixture.a.release(task, task.getFreezeAmount());
    }

    private void walletFinish(VideoTask task, BigDecimal amount) {
        WalletService wallet = fixture.first.getBean(WalletService.class);
        if ("SUCCESS".equals(task.getStatus())) wallet.settle(7L, amount, task.getId());
        else wallet.release(7L, amount, task.getId());
    }

    private ApiKeyBudgetReconcileTask scanner(VideoTask... tasks) {
        // Only task lookup is stubbed (the shared schema omits unrelated VideoTask columns).
        // Reconcile loop, scan SQL, billing proxies, budget and wallet are real.
        VideoTaskService lookup = mock(VideoTaskService.class);
        for (VideoTask task : tasks) when(lookup.getById(task.getId())).thenReturn(task);
        return new ApiKeyBudgetReconcileTask(fixture.budgets, fixture.a, lookup);
    }

    private String expectedState(String terminal) { return "SUCCESS".equals(terminal) ? "SETTLED" : "RELEASED"; }
    private String state(long id) {
        return fixture.db.queryForObject("SELECT state FROM api_key_budget_authorization WHERE task_id=?", String.class, id);
    }
    private List<Long> reservedIds() {
        return fixture.budgets.findReserved(0, 100).stream().map(hold -> hold.taskId()).toList();
    }
    private int count(String sql) { return fixture.db.queryForObject(sql, Integer.class); }
    private void money(String sql, String expected) {
        BigDecimal actual = fixture.db.queryForObject(sql, BigDecimal.class);
        assertNotNull(actual, sql);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), sql);
    }
}
