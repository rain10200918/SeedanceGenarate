package org.example.seedancegenarate.service;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.example.seedancegenarate.service.ApiKeyBudgetService.TerminalState.*;
import static org.example.seedancegenarate.service.ApiKeyBudgetService.TransitionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyBudgetServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ClockedBudget budget;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:budget_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE api_key(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "status VARCHAR(16), expires_at TIMESTAMP)");
        jdbc.update("INSERT INTO api_key VALUES (11, 1, 'ENABLED', NULL), (12, 2, 'ENABLED', NULL)");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V58__api_key_budget.sql")).execute(ds);
        budget = new ClockedBudget(jdbc);
        login(1, "USER");
    }

    @AfterEach
    void clearContext() { UserContext.clear(); }

    private <T> T inTx(Supplier<T> work) { return tx.execute(status -> work.get()); }
    private void login(long id, String role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setRole(role);
        UserContext.setUser(user);
    }
    private BigDecimal money(String value) { return new BigDecimal(value); }
    private void limit(String value, long version) {
        inTx(() -> budget.setLimit(1, 11, value == null ? null : money(value), version));
    }
    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, money(expected).compareTo(actual));
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // 【测什么】无限额度仍追踪预占消费，同态重放不重复流水，相反终态拒绝。
    // 【怎么算红】删除状态分支或流水唯一约束使用路径，重复结算会多记消费或释放成功。
    @Test
    void unlimitedTracksAndTerminalReplayIsIdempotent() {
        assertEquals(APPLIED, inTx(() -> budget.reserve(11, 1, 101, money("3.20"))));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.reserve(11, 1, 101, money("3.20"))));
        var held = inTx(() -> budget.query(1, 11));
        assertNull(held.limit());
        assertNull(held.remaining());
        assertMoney("3.20", held.reserved());
        assertEquals(APPLIED, inTx(() -> budget.finish(11, 1, 101, money("3.20"), SETTLED)));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.finish(11, 1, 101, money("3.20"), SETTLED)));
        assertThrows(BusinessException.class,
                () -> inTx(() -> budget.finish(11, 1, 101, money("3.20"), RELEASED)));
        var done = inTx(() -> budget.query(1, 11));
        assertMoney("3.20", done.consumed());
        assertMoney("0", done.reserved());
        assertEquals(2, count("api_key_budget_ledger"));
    }

    // 【测什么】零预算拒绝付费但接受零价任务，错误码与HTTP状态遵守公开契约。
    // 【怎么算红】去掉预算条件会放过付费；零价路径不写授权或允许双终态也会失败。
    @Test
    void zeroBlocksPaidAndAllowsFree() {
        limit("0", 0);
        ApiException error = assertThrows(ApiException.class,
                () -> inTx(() -> budget.reserve(11, 1, 101, money("0.01"))));
        assertEquals("API_KEY_SPENDING_LIMIT_EXCEEDED", error.getCode());
        assertEquals(403, error.getHttpStatus().value());
        assertEquals(0, count("api_key_budget_authorization"));
        assertEquals(0, count("api_key_budget_ledger"));
        assertEquals(APPLIED, inTx(() -> budget.reserve(11, 1, 102, money("0"))));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.reserve(11, 1, 102, money("0.00"))));
        assertEquals(APPLIED, inTx(() -> budget.finish(11, 1, 102, money("0"), SETTLED)));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.finish(11, 1, 102, money("0"), SETTLED)));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.finish(11, 1, 102, money("0"), RELEASED)));
        inTx(() -> budget.reserve(11, 1, 103, money("0")));
        assertEquals(APPLIED, inTx(() -> budget.finish(11, 1, 103, money("0"), RELEASED)));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.finish(11, 1, 103, money("0"), RELEASED)));
        var view = inTx(() -> budget.query(1, 11));
        assertMoney("0", view.reserved());
        assertMoney("0", view.consumed());
        assertMoney("0", view.remaining());
        assertEquals(4, count("api_key_budget_ledger"));
    }

    // 【测什么】配置降低到占用以下立即阻新但允许原授权结算，余额展示不为负。
    // 【怎么算红】结算重新套当前预算会拒绝旧授权，剩余不clamp会返回负数。
    @Test
    void tighteningPreservesAcceptedHold() {
        limit("10", 0);
        inTx(() -> budget.reserve(11, 1, 101, money("8")));
        limit("2", 1);
        assertMoney("0", inTx(() -> budget.query(1, 11)).remaining());
        assertThrows(ApiException.class, () -> inTx(() -> budget.reserve(11, 1, 102, money("1"))));
        inTx(() -> budget.finish(11, 1, 101, money("8"), SETTLED));
        assertMoney("8", inTx(() -> budget.query(1, 11)).consumed());
    }

    // 【测什么】上海月界由数据库UTC瞬间决定，跨月重放和完成仅改变原月，新月延用预算。
    // 【怎么算红】用UTC月份或finish当前月份更新，十月预占/九月消费断言会失败。
    @Test
    void databaseUtcBoundaryKeepsOriginalMonthForSettleAndRelease() {
        limit("10", 0);
        inTx(() -> budget.reserve(11, 1, 101, money("4")));
        inTx(() -> budget.reserve(11, 1, 102, money("2")));
        assertEquals("2026-09", inTx(() -> budget.query(1, 11)).period());
        budget.now = Instant.parse("2026-09-30T16:00:00Z");
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.reserve(11, 1, 101, money("4"))));
        assertEquals(1, count("api_key_budget_period"));
        inTx(() -> budget.reserve(11, 1, 103, money("3")));
        inTx(() -> budget.finish(11, 1, 101, money("4"), SETTLED));
        inTx(() -> budget.finish(11, 1, 102, money("2"), RELEASED));
        var october = inTx(() -> budget.query(1, 11));
        assertEquals("2026-10", october.period());
        assertEquals("2026-11-01T00:00+08:00", october.resetAt().toString());
        assertEquals("CNY", october.currency());
        assertMoney("10", october.limit());
        assertMoney("0", october.consumed());
        assertMoney("3", october.reserved());
        assertMoney("7", october.remaining());
        assertMoney("4", jdbc.queryForObject("SELECT consumed FROM api_key_budget_period "
                + "WHERE api_key_id=11 AND period='2026-09'", BigDecimal.class));
        assertMoney("0", jdbc.queryForObject("SELECT reserved FROM api_key_budget_period "
                + "WHERE api_key_id=11 AND period='2026-09'", BigDecimal.class));
    }

    // 【测什么】actor必须等于当前会话，只有ADMIN能跨属主；普通属主不可提高或清空有限预算。
    // 【怎么算红】只信actorId或移除owner过滤会放行跨账号查询，移除收紧判断会允许提额。
    @Test
    void actorAndOwnerIsolationAndAdminRaising() {
        assertEquals(404, assertThrows(BusinessException.class, () -> inTx(() -> budget.query(2, 12))).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> inTx(() -> budget.query(1, 12))).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> inTx(() -> budget.setLimit(2, 12, money("10"), 0))).getCode());
        limit("10", 0);
        assertEquals(403, assertThrows(BusinessException.class, () -> limit("11", 1)).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> limit(null, 1)).getCode());
        login(9, "ADMIN");
        assertEquals(404, assertThrows(BusinessException.class, () -> inTx(() -> budget.query(1, 11))).getCode());
        inTx(() -> budget.setLimit(9, 11, money("9999999999.99"), 1));
        var cleared = inTx(() -> budget.setLimit(9, 11, null, 2));
        assertNull(cleared.limit());
        assertEquals(3, cleared.version());
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, () -> inTx(() -> budget.query(1, 11))).getCode());
    }

    // 【测什么】负数、超精度、溢出、空金额及非法版本均拒绝，配置旧版本不覆盖新值。
    // 【怎么算红】静默舍入金额或删除expectedVersion判断会让至少一项非法操作成功。
    @Test
    void validatesAmountsAndConfigVersion() {
        for (String bad : new String[]{"-0.01", "0.001", "1.000", "10000000000.00"}) {
            assertEquals(400, assertThrows(BusinessException.class, () -> limit(bad, 0)).getCode());
            assertThrows(BusinessException.class, () -> inTx(() -> budget.reserve(11, 1, 101, money(bad))));
        }
        assertThrows(BusinessException.class, () -> inTx(() -> budget.reserve(11, 1, 101, null)));
        assertThrows(BusinessException.class, () -> limit("1", -1));
        limit("5", 0);
        assertEquals(409, assertThrows(BusinessException.class, () -> limit("3", 0)).getCode());
        var view = inTx(() -> budget.query(1, 11));
        assertMoney("5", view.limit());
        assertEquals(1, view.version());
        inTx(() -> budget.reserve(11, 1, 101, money("5")));
        assertMoney("0", inTx(() -> budget.query(1, 11)).remaining());
        assertEquals(1, inTx(() -> budget.query(1, 11)).version());
    }

    // 【测什么】后台操作无会话也可用；不存在授权兼容历史，金额或身份错配绝不改变账目。
    // 【怎么算红】finish信任传入金额或reserve重放未比对绑定，会错误结算或跨Key复用。
    @Test
    void backgroundNoContextAndImmutableBinding() {
        UserContext.clear();
        assertEquals(NO_RECORD, inTx(() -> budget.finish(11, 1, 999, money("1"), SETTLED)));
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.reserve(11, 1, 101, money("3"))));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.reserve(12, 2, 101, money("2"))));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.finish(11, 1, 101, money("3"), SETTLED)));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.finish(11, 2, 101, money("2"), SETTLED)));
        assertEquals(APPLIED, inTx(() -> budget.finish(11, 1, 101, money("2"), RELEASED)));
        assertEquals(ALREADY_APPLIED, inTx(() -> budget.finish(11, 1, 101, money("2"), RELEASED)));
        assertThrows(BusinessException.class, () -> inTx(() -> budget.reserve(11, 1, 101, money("2"))));
        assertEquals(2, count("api_key_budget_ledger"));
    }

    // 【测什么】新预占锁内检查禁用/过期，既有授权即使Key禁用过期仍可结算或释放。
    // 【怎么算红】reserve不查状态会放过102/103，finish复用启用校验会拒绝旧任务结算。
    @Test
    void revalidatesEnabledAndExpiryOnlyOnReserve() {
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        jdbc.update("UPDATE api_key SET status='DISABLED' WHERE id=11");
        assertEquals("API_KEY_DISABLED", assertThrows(ApiException.class,
                () -> inTx(() -> budget.reserve(11, 1, 102, money("1")))).getCode());
        inTx(() -> budget.finish(11, 1, 101, money("2"), SETTLED));
        jdbc.update("UPDATE api_key SET status='ENABLED', expires_at='2026-09-30 23:59:59' WHERE id=11");
        assertEquals("API_KEY_EXPIRED", assertThrows(ApiException.class,
                () -> inTx(() -> budget.reserve(11, 1, 103, money("1")))).getCode());
    }

    // 【测什么】钱包阶段抛错时预算授权、流水和预占全部跟随外层事务回滚。
    // 【怎么算红】预算使用REQUIRES_NEW或独立连接，会残留授权/流水/预占。
    @Test
    void outerFailureRollsBackAllBudgetFacts() {
        assertThrows(IllegalStateException.class, () -> inTx(() -> {
            budget.reserve(11, 1, 101, money("2"));
            throw new IllegalStateException("simulated wallet failure");
        }));
        assertEquals(0, count("api_key_budget_authorization"));
        assertEquals(0, count("api_key_budget_ledger"));
        assertEquals(0, count("api_key_budget_period"));
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        assertThrows(IllegalStateException.class, () -> inTx(() -> {
            budget.finish(11, 1, 101, money("2"), RELEASED);
            throw new IllegalStateException("simulated wallet release failure");
        }));
        assertEquals(1, budget.findReserved(0, 10).size());
        assertEquals(1, count("api_key_budget_ledger"));
        assertMoney("2", inTx(() -> budget.query(1, 11)).reserved());
    }

    // 【测什么】账目异常导致条件更新失败时，先前状态CAS及终态流水也须回滚。
    // 【怎么算红】吞掉更新0行或把流水/CAS拆事务，失败后会残留SETTLED或FINISH。
    @Test
    void insufficientPeriodCounterRollsBackTerminalCasAndLedger() {
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        jdbc.update("UPDATE api_key_budget_period SET reserved=0 WHERE api_key_id=11");
        assertThrows(BusinessException.class, () -> inTx(() -> budget.finish(11, 1, 101, money("2"), SETTLED)));
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT state FROM api_key_budget_authorization WHERE task_id=101", String.class));
        assertEquals(1, count("api_key_budget_ledger"));
    }

    // 【测什么】两个服务实例通过独立连接争同Key额度，最多一个8元预占成功。
    // 【怎么算红】预算读取不持有Key/period锁，会让两个请求都在10元余额上通过。
    @Test
    void twoInstancesCannotOverReserve() throws Exception {
        limit("10", 0);
        var second = new ClockedBudget(jdbc);
        var results = race(() -> inTx(() -> budget.reserve(11, 1, 101, money("8"))),
                () -> inTx(() -> second.reserve(11, 1, 102, money("8"))));
        assertEquals(1, results.stream().filter(APPLIED::equals).count());
        assertEquals(1, results.stream().filter(ApiException.class::isInstance).count());
        assertMoney("8", inTx(() -> budget.query(1, 11)).reserved());
        assertEquals(1, count("api_key_budget_authorization"));
    }

    // 【测什么】同任务同时重放只有一次预占；并发相反终态仅一方赢且仅一条终态流水。
    // 【怎么算红】删除同态检测或按终态分别建唯一键，会重复预占或产生双终态。
    @Test
    void concurrentReplayAndOppositeTerminalHaveOneWinner() throws Exception {
        var second = new ClockedBudget(jdbc);
        var reserveResults = race(() -> inTx(() -> budget.reserve(11, 1, 101, money("2"))),
                () -> inTx(() -> second.reserve(11, 1, 101, money("2"))));
        assertTrue(reserveResults.contains(APPLIED));
        assertTrue(reserveResults.contains(ALREADY_APPLIED));
        var results = race(() -> inTx(() -> budget.finish(11, 1, 101, money("2"), SETTLED)),
                () -> inTx(() -> second.finish(11, 1, 101, money("2"), RELEASED)));
        assertEquals(1, results.stream().filter(APPLIED::equals).count());
        assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
        assertEquals(2, count("api_key_budget_ledger"));
        assertMoney("0", inTx(() -> budget.query(1, 11)).reserved());
    }

    // 【测什么】两个窗口用相同配置版本并发保存，恰好一个成功，另一个409。
    // 【怎么算红】移除version条件或版本比较，两次不同配置会都成功。
    @Test
    void concurrentSettingsUseVersionCas() throws Exception {
        limit("10", 0);
        var second = new ClockedBudget(jdbc);
        var results = race(() -> inTx(() -> budget.setLimit(1, 11, money("7"), 1)),
                () -> inTx(() -> second.setLimit(1, 11, money("8"), 1)));
        assertEquals(1, results.stream().filter(org.example.seedancegenarate.dto.ApiKeyBudgetView.class::isInstance).count());
        assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
        assertEquals(2, inTx(() -> budget.query(1, 11)).version());
    }

    // 【测什么】配置收紧与预占串行化，先获锁者决定是否保留旧授权，之后新付费均被挡住。
    // 【怎么算红】配置不拿Key锁会让预占用旧额度在降低之后成功且产生不可解释中间值。
    @Test
    void settingsAndReservationShareLock() throws Exception {
        limit("10", 0);
        var second = new ClockedBudget(jdbc);
        var results = race(() -> inTx(() -> budget.reserve(11, 1, 101, money("8"))),
                () -> inTx(() -> second.setLimit(1, 11, money("2"), 1)));
        assertInstanceOf(org.example.seedancegenarate.dto.ApiKeyBudgetView.class, results.get(1));
        assertTrue(results.get(0) == APPLIED || results.get(0) instanceof ApiException);
        assertMoney(results.get(0) == APPLIED ? "8" : "0", inTx(() -> budget.query(1, 11)).reserved());
        assertThrows(ApiException.class, () -> inTx(() -> budget.reserve(11, 1, 102, money("3"))));
    }

    // 【测什么】授权扫描独立于钱包，按任务游标分页，只返回RESERVED且无会话要求。
    // 【怎么算红】删除state过滤或游标条件，会重复返回已终态/上一页的任务。
    @Test
    void compensationScanIsBoundedAndIndependentOfWallet() {
        UserContext.clear();
        inTx(() -> budget.reserve(11, 1, 103, money("3")));
        inTx(() -> budget.reserve(11, 1, 101, money("1")));
        inTx(() -> budget.reserve(11, 1, 102, money("2")));
        inTx(() -> budget.finish(11, 1, 102, money("2"), RELEASED));
        var first = budget.findReserved(0, 1);
        assertEquals(101, first.get(0).taskId());
        assertEquals(11, first.get(0).apiKeyId());
        assertEquals("2026-09", first.get(0).period());
        assertMoney("1", first.get(0).amount());
        var second = budget.findReserved(first.get(0).taskId(), 1);
        assertEquals(103, second.get(0).taskId());
        assertTrue(budget.findReserved(103, 10).isEmpty());
        assertThrows(BusinessException.class, () -> budget.findReserved(-1, 10));
        assertThrows(BusinessException.class, () -> budget.findReserved(0, 1001));
    }

    // 【测什么】预占/结算必须加入可写事务，扫描不标记readOnly，避免补偿事实路由从库。
    // 【怎么算红】删除事务守卫或将扫描标readOnly，直接调用/反射断言会失败。
    @Test
    void transactionBoundariesFailClosed() throws Exception {
        assertThrows(IllegalStateException.class, () -> budget.reserve(11, 1, 101, money("1")));
        assertThrows(IllegalStateException.class, () -> budget.finish(11, 1, 101, money("1"), SETTLED));
        var reserve = ApiKeyBudgetService.class.getMethod("reserve", long.class, long.class, long.class, BigDecimal.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        var finish = ApiKeyBudgetService.class.getMethod("finish", long.class, long.class, long.class,
                        BigDecimal.class, ApiKeyBudgetService.TerminalState.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY, reserve.propagation());
        assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY, finish.propagation());
        assertFalse(ApiKeyBudgetService.class.getMethod("findReserved", long.class, int.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }

    // 【测什么】数据库唯一FINISH槽位物理阻止同任务同时出现结算与释放流水。
    // 【怎么算红】把主键改为(task_id,state)，第二个相反终态INSERT会成功。
    @Test
    void databaseLedgerHasOneTerminalSlot() {
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        inTx(() -> budget.finish(11, 1, 101, money("2"), SETTLED));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO api_key_budget_ledger(task_id,phase,state,api_key_id,user_id,period,amount) "
                        + "VALUES (101,'FINISH','RELEASED',11,1,'2026-09',2)"));
        assertEquals(2, count("api_key_budget_ledger"));
    }

    // 【测什么】生产时间读取方法真实执行数据库epoch SQL，SQL失败不可退回JVM时间。
    // 【怎么算红】移除数据库调用改用Instant.now，拒绝JdbcTemplate的第二项断言不会抛错。
    @Test
    void productionClockUsesDatabaseAndNeverFallsBack() {
        Instant before = Instant.now().minusSeconds(2);
        Instant actual = new ApiKeyBudgetService(jdbc).databaseNow();
        assertFalse(actual.isBefore(before));
        assertFalse(actual.isAfter(Instant.now().plusSeconds(2)));
        var unavailable = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> type) {
                throw new org.springframework.dao.DataAccessResourceFailureException("test unavailable");
            }
        };
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> new ApiKeyBudgetService(unavailable).databaseNow());
    }

    // 【测什么】既有8元预占后收紧至0，仍可新增零价任务且不撤销原冻结。
    // 【怎么算红】只删除amount.signum()>0保护，8+0>0会错误拒绝零价任务。
    @Test
    void zeroCostAllowedWhenPriorUsageExceedsZeroLimit() {
        limit("10", 0);
        inTx(() -> budget.reserve(11, 1, 101, money("8")));
        limit("0", 1);
        assertEquals(APPLIED, inTx(() -> budget.reserve(11, 1, 102, money("0"))));
        assertMoney("8", inTx(() -> budget.query(1, 11)).reserved());
        assertEquals(APPLIED, inTx(() -> budget.finish(11, 1, 102, money("0"), SETTLED)));
        assertMoney("0", inTx(() -> budget.query(1, 11)).remaining());
        assertThrows(ApiException.class, () -> inTx(() -> budget.reserve(11, 1, 103, money("0.01"))));
        inTx(() -> budget.finish(11, 1, 101, money("8"), RELEASED));
        assertMoney("0", inTx(() -> budget.query(1, 11)).reserved());
    }

    // 【测什么】模拟普通SELECT旧快照看不到授权，reserve重放/finish仍依靠当前读处理原授权。
    // 【怎么算红】把hold查询改回非锁读，会返回空快照而重复插入或错误返回NO_RECORD。
    @Test
    void staleSnapshotCannotDecideAuthorizationAbsence() {
        inTx(() -> budget.reserve(11, 1, 101, money("2")));
        var staleReads = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> java.util.List<T> query(String sql,
                    org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                if (sql.contains("FROM api_key_budget_authorization WHERE task_id=?")
                        && !sql.contains("FOR UPDATE")) return java.util.List.of();
                return super.query(sql, mapper, args);
            }
        };
        var second = new ClockedBudget(staleReads);
        second.now = Instant.parse("2026-09-30T16:00:00Z");
        assertEquals(ALREADY_APPLIED, inTx(() -> second.reserve(11, 1, 101, money("2"))));
        assertEquals(APPLIED, inTx(() -> second.finish(11, 1, 101, money("2"), SETTLED)));
        assertEquals(1, count("api_key_budget_period"));
        assertMoney("2", jdbc.queryForObject("SELECT consumed FROM api_key_budget_period "
                + "WHERE api_key_id=11 AND period='2026-09'", BigDecimal.class));
    }

    // 【测什么】同账号不同Key在三张预算表全空时独立初始化，两笔授权均可成功。
    // 【怎么算红】初始化路径相互死锁/误认重放会导致某个结果非APPLIED或少记金额。
    @Test
    void differentKeysInitializeEmptyBudgetTables() throws Exception {
        jdbc.update("UPDATE api_key SET user_id=1 WHERE id=12");
        var second = new ClockedBudget(jdbc);
        var results = race(() -> inTx(() -> budget.reserve(11, 1, 101, money("2"))),
                () -> inTx(() -> second.reserve(12, 1, 102, money("3"))));
        assertEquals(java.util.List.of(APPLIED, APPLIED), results);
        assertEquals(2, count("api_key_budget_authorization"));
        assertEquals(2, count("api_key_budget_config"));
        assertEquals(2, count("api_key_budget_period"));
        assertMoney("5", jdbc.queryForObject("SELECT SUM(reserved) FROM api_key_budget_period", BigDecimal.class));
    }

    // 【测什么】初始化先INSERT再锁已有行；upsert返回0或2都不能改变首次授权/重放判断。
    // 【怎么算红】回到查空锁再插入会触发顺序断言；依赖affectedRows会把首次或重放判断错。
    @Test
    void initializationPrecedesLockingReadsAndIgnoresAffectedRows() {
        for (int reported : new int[]{0, 2}) {
            var initialized = new java.util.HashSet<String>();
            var observed = new JdbcTemplate(jdbc.getDataSource()) {
                @Override public int update(String sql, Object... args) {
                    int actual = super.update(sql, args);
                    if (sql.contains("ON DUPLICATE KEY UPDATE")) {
                        initialized.add(sql.split(" ")[2].split("\\(")[0]);
                        return reported;
                    }
                    return actual;
                }
                @Override public <T> java.util.List<T> query(String sql,
                        org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                    for (String table : new String[]{"api_key_budget_config", "api_key_budget_period",
                            "api_key_budget_authorization"}) {
                        if (sql.contains("FROM " + table + " ") && sql.contains("FOR UPDATE")) {
                            assertTrue(initialized.contains(table), "locking read before initialization: " + table);
                        }
                    }
                    return super.query(sql, mapper, args);
                }
            };
            var service = new ClockedBudget(observed);
            long task = 200 + reported;
            assertEquals(APPLIED, inTx(() -> service.reserve(11, 1, task, money("2"))));
            String token = jdbc.queryForObject("SELECT insertion_token FROM api_key_budget_authorization "
                    + "WHERE task_id=?", String.class, task);
            assertNotNull(token);
            assertEquals(ALREADY_APPLIED, inTx(() -> service.reserve(11, 1, task, money("2"))));
            assertEquals(token, jdbc.queryForObject("SELECT insertion_token FROM api_key_budget_authorization "
                    + "WHERE task_id=?", String.class, task));
        }
        assertEquals(2, count("api_key_budget_ledger"));
        assertMoney("4", inTx(() -> budget.query(1, 11)).reserved());
    }

    private java.util.List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Object> one = pool.submit(() -> contender(start, first));
            Future<Object> two = pool.submit(() -> contender(start, second));
            return java.util.List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Object contender(CyclicBarrier start, Supplier<?> work) throws Exception {
        login(1, "USER");
        try {
            start.await(5, TimeUnit.SECONDS);
            return work.get();
        } catch (RuntimeException error) { return error; }
        finally { UserContext.clear(); }
    }

    private static class ClockedBudget extends ApiKeyBudgetService {
        volatile Instant now = Instant.parse("2026-09-30T15:59:59Z");
        ClockedBudget(JdbcTemplate jdbc) { super(jdbc); }
        @Override protected Instant databaseNow() { return now; }
    }
}
