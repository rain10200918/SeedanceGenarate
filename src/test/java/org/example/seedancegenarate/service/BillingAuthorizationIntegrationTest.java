package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.*;
import org.example.seedancegenarate.service.Impl.WalletServiceImpl;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real budget + real wallet mappers, two independent Spring service instances on one database. */
class BillingAuthorizationIntegrationTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ApiKeyBudgetService budgets(JdbcTemplate db) { return new ApiKeyBudgetService(db); }
        @Bean SqlSessionTemplate session(DataSource ds) throws Exception {
            var config = new MybatisConfiguration();
            config.addMapper(WalletMapper.class); config.addMapper(BalanceTransactionMapper.class);
            config.addMapper(RechargeOrderMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds); factory.setConfiguration(config);
            return new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        }
        @Bean WalletService wallet(SqlSessionTemplate sql) {
            return new WalletServiceImpl(sql.getMapper(WalletMapper.class),
                    sql.getMapper(BalanceTransactionMapper.class), sql.getMapper(RechargeOrderMapper.class));
        }
        @Bean BillingAuthorizationService billing(JdbcTemplate db, ApiKeyBudgetService budgets, WalletService wallet) {
            return new BillingAuthorizationService(db, budgets, wallet);
        }
    }
    AnnotationConfigApplicationContext first, second;
    JdbcTemplate db;
    BillingAuthorizationService a, b;
    TransactionTemplate tx;
    ApiKeyBudgetService budgets;

    @BeforeEach void setup() {
        String url = System.getProperty("budget.test.jdbcUrl");
        if (url == null) url = "jdbc:h2:mem:billing-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        else assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13379/key_budget_test?"), "Only the isolated test database is allowed");
        var ds = new DriverManagerDataSource(url, url.startsWith("jdbc:mysql:") ? "root" : "sa", "");
        db = new JdbcTemplate(ds);
        // Exact test-owned tables only; no application configuration or credentials are loaded.
        for (String table : List.of("api_key_budget_ledger", "api_key_budget_authorization", "api_key_budget_period",
                "api_key_budget_config", "balance_transaction", "wallet", "video_task", "api_key")) {
            db.execute("DROP TABLE IF EXISTS " + table);
        }
        db.execute("CREATE TABLE api_key(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(16),expires_at TIMESTAMP NULL)");
        db.execute("CREATE TABLE video_task(id BIGINT PRIMARY KEY,user_id BIGINT,api_key_id BIGINT,status VARCHAR(16),freeze_amount DECIMAL(12,2))");
        db.execute("CREATE TABLE wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(12,2),frozen DECIMAL(12,2),version INT DEFAULT 0,create_time TIMESTAMP NULL,update_time TIMESTAMP NULL)");
        db.execute("CREATE TABLE balance_transaction(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,type VARCHAR(32),amount DECIMAL(12,2),hold_amount DECIMAL(12,2),balance_after DECIMAL(12,2),frozen_after DECIMAL(12,2),biz_key VARCHAR(128) UNIQUE,task_id BIGINT,ref_order_no VARCHAR(64),operator_id BIGINT,operator_name VARCHAR(64),coupon_id BIGINT,remark VARCHAR(255),create_time TIMESTAMP NULL)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V58__api_key_budget.sql")).execute(ds);
        db.update("INSERT INTO api_key(id,user_id,status) VALUES(1,7,'ENABLED'),(2,7,'ENABLED')");
        db.update("INSERT INTO wallet(user_id,balance,frozen) VALUES(7,100,0)");
        first = context(ds); second = context(new DriverManagerDataSource(url, url.startsWith("jdbc:mysql:") ? "root" : "sa", ""));
        a = first.getBean(BillingAuthorizationService.class); b = second.getBean(BillingAuthorizationService.class);
        tx = new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
        budgets = first.getBean(ApiKeyBudgetService.class);
        AppUser admin = new AppUser(); admin.setId(99L); admin.setRole("ADMIN"); UserContext.setUser(admin);
    }
    private AnnotationConfigApplicationContext context(DataSource ds) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(DataSource.class, () -> ds); ctx.register(Config.class); ctx.refresh(); return ctx;
    }
    @AfterEach void cleanup() {
        UserContext.clear();
        if (first != null) first.close(); if (second != null) second.close();
    }
    private VideoTask task(long id, long key, String amount) {
        VideoTask task = new VideoTask(); task.setId(id); task.setUserId(7L); task.setApiKeyId(key);
        task.setStatus("PROCESSING"); task.setFreezeAmount(new BigDecimal(amount));
        db.update("INSERT INTO video_task VALUES(?,7,?,'PROCESSING',?)", id, key, task.getFreezeAmount());
        return task;
    }
    private void terminal(VideoTask task, String status) { db.update("UPDATE video_task SET status=? WHERE id=?", status, task.getId()); task.setStatus(status); }
    private void money(String sql, String value) { assertEquals(0, new BigDecimal(value).compareTo(db.queryForObject(sql, BigDecimal.class)), sql); }
    // 【测什么】两个独立服务实例竞争同Key预算只能受理一次，钱包和预占相符。
    // 【怎么算红】删除预算上限检查会让两笔都受理，accepted=2。
    @Test void twoInstancesCannotOverspendOneKey() throws Exception {
        budgets.setLimit(99, 1, new BigDecimal("30"), 0);
        var x = task(101, 1, "20"); var y = task(102, 1, "20");
        assertEquals(1, race(() -> a.freeze(x, x.getFreezeAmount()), () -> b.freeze(y, y.getFreezeAmount())));
        money("SELECT balance FROM wallet", "80"); money("SELECT frozen FROM wallet", "20");
        money("SELECT SUM(reserved) FROM api_key_budget_period", "20");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM api_key_budget_authorization", Integer.class));
    }

    // 【测什么】同账号不同Key各有额度仍不能合计超出钱包，失败方预算回滚。
    // 【怎么算红】将钱包冻结移到预算事务外，会留下两笔预占或余额不正确。
    @Test void differentKeysShareTheAccountWallet() throws Exception {
        db.update("UPDATE wallet SET balance=30");
        var x = task(101, 1, "20"); var y = task(102, 2, "20");
        assertEquals(1, race(() -> a.freeze(x, x.getFreezeAmount()), () -> b.freeze(y, y.getFreezeAmount())));
        money("SELECT balance FROM wallet", "10"); money("SELECT SUM(reserved) FROM api_key_budget_period", "20");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM api_key_budget_ledger", Integer.class));
    }

    // 【测什么】事务后续attempt/job异常使钱包、Key流水和授权全部回滚。
    // 【怎么算红】将facade设REQUIRES_NEW会让内层预占在外层失败后残留。
    @Test void laterJobFailureRollsBackBothLedgers() {
        var x = task(101, 1, "20");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            a.freeze(x, x.getFreezeAmount()); throw new IllegalStateException("fake enqueue failure");
        }));
        money("SELECT balance FROM wallet", "100");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM api_key_budget_authorization", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM balance_transaction", Integer.class));
    }

    // 【测什么】受理响应丢失后同task由另一实例重放不重复预占，重复成功不双扣。
    // 【怎么算红】移除同task授权/钱包流水幂等会变为40元而非20元。
    @Test void replayAndRepeatedSettlementAreIdempotent() throws Exception {
        var x = task(101, 1, "20");
        assertEquals(2, race(() -> a.freeze(x, x.getFreezeAmount()), () -> b.freeze(x, x.getFreezeAmount())));
        terminal(x, "SUCCESS"); a.settle(x, x.getFreezeAmount()); b.settle(x, x.getFreezeAmount());
        money("SELECT balance FROM wallet", "80"); money("SELECT frozen FROM wallet", "0");
        money("SELECT consumed FROM api_key_budget_period", "20"); money("SELECT reserved FROM api_key_budget_period", "0");
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM balance_transaction", Integer.class));
    }

    // 【测什么】失败释放时钱包异常使预算释放一并回滚，修正后可重试且不双退。
    // 【怎么算红】拆开两个释放事务会在钱包异常时让预算提前回到0。
    @Test void failedReleaseIsAtomicAndCanBeRetried() {
        var x = task(101, 1, "20"); a.freeze(x, x.getFreezeAmount()); terminal(x, "FAILED");
        db.update("UPDATE wallet SET frozen=0");
        assertThrows(IllegalStateException.class, () -> a.release(x, x.getFreezeAmount()));
        money("SELECT reserved FROM api_key_budget_period", "20");
        assertEquals("RESERVED", db.queryForObject("SELECT state FROM api_key_budget_authorization", String.class));
        db.update("UPDATE wallet SET frozen=20"); b.release(x, x.getFreezeAmount()); a.release(x, x.getFreezeAmount());
        money("SELECT balance FROM wallet", "100"); money("SELECT reserved FROM api_key_budget_period", "0");
    }

    // 【测什么】迟到失败不能释放已成功任务，也不能挪用别的任务冻结额。
    // 【怎么算红】移除facade持久终态校验会让错误调用继续到预算/钱包。
    @Test void durableTerminalRejectsTheOppositeAction() {
        var x = task(101, 1, "20"); a.freeze(x, x.getFreezeAmount()); terminal(x, "SUCCESS");
        assertThrows(IllegalStateException.class, () -> b.release(x, x.getFreezeAmount()));
        a.settle(x, x.getFreezeAmount());
        assertThrows(IllegalStateException.class, () -> b.release(x, x.getFreezeAmount()));
        money("SELECT balance FROM wallet", "80"); money("SELECT consumed FROM api_key_budget_period", "20");
    }

    // 【测什么】历史API任务无预算授权仍沿已有钱包冻结流水正确结算。
    // 【怎么算红】将NO_RECORD改成拒绝会阻塞历史在途任务收尾。
    @Test void legacyApiTaskSettlesWithoutInventingBudgetHistory() {
        var x = task(101, 1, "20"); first.getBean(WalletService.class).freeze(7L, x.getFreezeAmount(), x.getId());
        terminal(x, "SUCCESS"); a.settle(x, x.getFreezeAmount());
        money("SELECT balance FROM wallet", "80"); money("SELECT frozen FROM wallet", "0");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM api_key_budget_authorization", Integer.class));
    }

    private int race(Runnable left, Runnable right) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (Runnable work : List.of(left, right)) futures.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                try { work.run(); return true; } catch (org.example.seedancegenarate.exception.ApiException
                        | WalletServiceImpl.InsufficientBalanceException e) { return false; }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int accepted = 0; for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) accepted++;
            return accepted;
        } finally { pool.shutdownNow(); }
    }
}
