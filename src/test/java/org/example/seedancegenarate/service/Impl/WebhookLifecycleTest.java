package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.entity.*;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.*;
import org.example.seedancegenarate.service.*;
import org.example.seedancegenarate.task.WebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(WebhookLifecycleTest.Config.class)
class WebhookLifecycleTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:webhook-a3;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds) throws Exception {
            var config = new MybatisConfiguration();
            config.addMapper(ApiKeyMapper.class); config.addMapper(WebhookDeliveryMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean ApiKeyMapper keys(SqlSessionTemplate session) { return session.getMapper(ApiKeyMapper.class); }
        @Bean WebhookDeliveryMapper deliveries(SqlSessionTemplate session) { return session.getMapper(WebhookDeliveryMapper.class); }
        @Bean ApiKeyServiceImpl service() { return new ApiKeyServiceImpl(); }
        @Bean WebhookCallbackClient callback() { return mock(WebhookCallbackClient.class); }
    }
    @Autowired ApiKeyService service;
    @Autowired ApiKeyMapper keys;
    @Autowired WebhookDeliveryMapper deliveries;
    @Autowired WebhookCallbackClient callback;
    @Autowired DataSource ds;
    @Autowired PlatformTransactionManager tm;
    JdbcTemplate db;
    WebhookDispatcher dispatcher;
    ApiCallLogMapper logs;
    WebhookCallbackClient.ValidatedTarget target;

    @BeforeEach void setup() throws Exception {
        db = new JdbcTemplate(ds);
        db.execute("DROP TABLE IF EXISTS webhook_delivery"); db.execute("DROP TABLE IF EXISTS api_key");
        db.execute("CREATE TABLE api_key (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, name VARCHAR(64),"
                + "key_prefix VARCHAR(16), key_hash VARCHAR(64), status VARCHAR(16), expires_at TIMESTAMP,"
                + "callback_url VARCHAR(512), webhook_secret VARCHAR(128), last_used_at TIMESTAMP,"
                + "max_concurrency INT, created_by BIGINT, created_ip VARCHAR(64), create_time TIMESTAMP, update_time TIMESTAMP)");
        db.execute("CREATE TABLE webhook_delivery (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64),"
                + "api_key_id BIGINT, status VARCHAR(16), payload CLOB, http_code INT, attempts INT,"
                + "next_retry_at TIMESTAMP, delivered BOOLEAN, create_time TIMESTAMP, update_time TIMESTAMP, UNIQUE(task_id,status))");
        db.update("INSERT INTO api_key(id,user_id,status,callback_url,webhook_secret) VALUES(1,7,'ENABLED',?,?)",
                "https://callback.example/hook", "original-secret");
        reset(callback); target = mock(WebhookCallbackClient.ValidatedTarget.class);
        when(target.url()).thenReturn("https://callback.example/hook");
        when(callback.validate(any())).thenAnswer(a -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "DNS outside transaction");
            String value = a.getArgument(0); return value == null || value.isBlank() ? null : target;
        });
        when(callback.post(any(), anyString(), anyString())).thenReturn(204);
        logs = mock(ApiCallLogMapper.class);
        var properties = new DistributedLockProperties(); properties.setEnabled(false);
        dispatcher = new WebhookDispatcher(logs, keys, deliveries, new ObjectMapper(),
                mock(DistributedLock.class), properties, callback);
    }

    private WebhookDelivery pending() {
        WebhookDelivery row = new WebhookDelivery(); row.setTaskId("task-1"); row.setApiKeyId(1L);
        row.setStatus("SUCCESS"); row.setPayload("{\"task_id\":\"task-1\"}"); row.setAttempts(0);
        row.setDelivered(false); row.setNextRetryAt(LocalDateTime.now().minusSeconds(1)); deliveries.insert(row);
        return row;
    }

    // 【测什么】跨owner不能改地址/轮换/撤销，且不触DNS和投递记录。
    // 【怎么算红】删掉SQL userId条件，原key状态或secret会改变。
    @Test void foreignOwnerCannotMutateOrResolve() {
        var row = pending();
        assertFalse(service.updateCallbackOwned(1L, 99L, "https://callback.example/hook"));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.rotateWebhookSecretOwned(1L, 99L)).getCode());
        assertFalse(service.revokeOwned(1L, 99L));
        verifyNoInteractions(callback);
        assertEquals("original-secret", keys.selectById(1L).getWebhookSecret());
        assertEquals("ENABLED", keys.selectById(1L).getStatus());
        assertEquals(0, deliveries.selectById(row.getId()).getAttempts());
    }

    // 【测什么】保存校验在事务外，清除真正写NULL并终止重试，不伪造成功。
    // 【怎么算红】DNS圈事务/NULL略过/设置delivered/漏终止任一变化会红。
    @Test void clearPersistsNullAndStopsWithoutSuccess() throws Exception {
        var row = pending();
        new TransactionTemplate(tm).execute(tx -> {
            assertTrue(service.updateCallbackOwned(1L, 7L, "https://callback.example/hook")); return null;
        });
        assertTrue(service.updateCallbackOwned(1L, 7L, " "));
        assertNull(keys.selectById(1L).getCallbackUrl()); assertStopped(row);
        dispatcher.retryPending(); verify(callback, never()).post(any(), any(), any());
    }

    // 【测什么】发出后清除，迟到异常或成功均不能覆写终止状态，重新配置也不复活旧投递。
    // 【怎么算红】去掉pendingWrite中的attempts条件，迟到失败会重新排期或成功会伪造delivered。
    @Test void lateFailureAndSuccessCannotResurrectClearedDelivery() throws Exception {
        for (boolean success : new boolean[]{false, true}) {
            db.update("DELETE FROM webhook_delivery");
            assertTrue(service.updateCallbackOwned(1L, 7L, "https://callback.example/hook"));
            var row = pending();
            doAnswer(a -> {
                service.updateCallbackOwned(1L, 7L, null);
                if (!success) throw new IOException("https://callback.example?secret=hidden");
                return 204;
            }).when(callback).post(any(), any(), any());
            dispatcher.retryPending(); assertStopped(row);
            assertTrue(service.updateCallbackOwned(1L, 7L, "https://callback.example/hook"));
            clearInvocations(callback); dispatcher.retryPending();
            verify(callback, never()).post(any(), any(), any());
        }
    }

    // 【测什么】DNS等待期间撤销不能触发后续网络请求。
    // 【怎么算红】删掉DNS后的key或投递重读，post零调用断言变红。
    @Test void cancellationDuringDnsStopsBeforeConnect() throws Exception {
        var row = pending();
        doAnswer(a -> { service.revokeOwned(1L, 7L); return target; }).when(callback).validate(any());
        dispatcher.retryPending(); assertStopped(row);
        verify(callback, never()).post(any(), any(), any());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.rotateWebhookSecretOwned(1L, 7L)).getCode());
    }

    // 【测什么】DNS等待期间清除再恢复同URL，旧delivery仍停止，不因key再次启用回调而重发。
    // 【怎么算红】移除DNS后delivery重读，只查当前key时会错误调用post。
    @Test void clearThenRestoreDuringDnsDoesNotReviveOldDelivery() throws Exception {
        var row = pending(); var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(a -> {
            String url = a.getArgument(0);
            if (first.getAndSet(false)) {
                service.updateCallbackOwned(1L, 7L, null);
                service.updateCallbackOwned(1L, 7L, "https://callback.example/hook");
            }
            return url == null || url.isBlank() ? null : target;
        }).when(callback).validate(any());
        dispatcher.retryPending(); assertStopped(row);
        verify(callback, never()).post(any(), any(), any());
    }

    // 【测什么】清除的key更新与投递终止必须同事务，终止SQL失败时callback不丢。
    // 【怎么算红】移除短事务，故障后callback会为NULL而不是原地址。
    @Test void clearRollsBackIfDeliveryUpdateFails() {
        db.execute("DROP TABLE webhook_delivery");
        assertThrows(RuntimeException.class, () -> service.updateCallbackOwned(1L, 7L, null));
        assertEquals("https://callback.example/hook", keys.selectById(1L).getCallbackUrl());
    }

    // 【测什么】轮换发生在DNS期间，本次实际发送采用新HMAC，原payload字节不变。
    // 【怎么算红】签名仍使用DNS前的key快照，X-Signature参数断言失败。
    @Test void rotationDuringDnsUsesLatestSecret() throws Exception {
        var row = pending();
        String[] secret = new String[1];
        doAnswer(a -> { secret[0] = service.rotateWebhookSecretOwned(1L, 7L); return target; })
                .when(callback).validate(any());
        dispatcher.retryPending();
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret[0].getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = java.util.HexFormat.of().formatHex(mac.doFinal(row.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verify(callback).post(target, row.getPayload(), signature);
        assertTrue(deliveries.selectById(row.getId()).getDelivered());
        assertNotEquals("original-secret", secret[0]);
    }

    // 【测什么】临时DNS503有限退避，下一次解析恢复可成功，连续503总共只消耗3次。
    // 【怎么算红】把503直接stop，第一次attempts=1及后续成功断言失败；增加预算也会失败。
    @Test void temporaryDnsFailureRetriesAndCanRecover() throws Exception {
        var row = pending();
        doThrow(new BusinessException(503, "回调地址暂时无法解析，请稍后重试"))
                .doReturn(target).when(callback).validate(any());
        dispatcher.retryPending();
        var first = deliveries.selectById(row.getId());
        assertEquals(1, first.getAttempts()); assertNotNull(first.getNextRetryAt()); assertFalse(first.getDelivered());
        verify(callback, never()).post(any(), any(), any());
        db.update("UPDATE webhook_delivery SET next_retry_at=? WHERE id=?", LocalDateTime.now().minusSeconds(1), row.getId());
        dispatcher.retryPending(); assertTrue(deliveries.selectById(row.getId()).getDelivered());
        verify(callback, times(1)).post(any(), any(), any());
        db.update("DELETE FROM webhook_delivery"); row = pending(); clearInvocations(callback);
        doThrow(new BusinessException(503, "回调地址暂时无法解析，请稍后重试")).when(callback).validate(any());
        for (int i = 1; i <= 3; i++) {
            dispatcher.retryPending(); assertEquals(i, deliveries.selectById(row.getId()).getAttempts());
            if (i < 3) db.update("UPDATE webhook_delivery SET next_retry_at=? WHERE id=?", LocalDateTime.now().minusSeconds(1), row.getId());
        }
        assertStopped(row); dispatcher.retryPending(); verify(callback, times(3)).validate(any());
        verify(callback, never()).post(any(), any(), any());
    }

    // 【测什么】旧HTTP/非公网配置校验失败后永久终止，不调用网络。
    // 【怎么算红】绕过validate或异常继续post，零投递/终止状态断言失败。
    @Test void legacyUnsafeCallbackIsBlocked() throws Exception {
        var row = pending(); db.update("UPDATE api_key SET callback_url='http://127.0.0.1/' WHERE id=1");
        doThrow(BusinessException.badRequest("unsafe")).when(callback).validate(any());
        dispatcher.retryPending(); assertStopped(row);
        verify(callback, never()).post(any(), any(), any());
    }

    // 【测什么】响应丢失/302/5xx按原预算重试至3次，2xx才成功，终止后不再重发。
    // 【怎么算红】把非2xx当成功或超限还排期，attempts/delivered/网络次数断言失败。
    @Test void atLeastOnceRetryBudgetAndNon2xx() throws Exception {
        var row = pending();
        doThrow(new IOException("lost response")).doReturn(302).doReturn(503)
                .when(callback).post(any(), any(), any());
        for (int i = 1; i <= 3; i++) {
            dispatcher.retryPending();
            var stored = deliveries.selectById(row.getId()); assertEquals(i, stored.getAttempts());
            assertFalse(stored.getDelivered());
            if (i < 3) {
                assertNotNull(stored.getNextRetryAt());
                db.update("UPDATE webhook_delivery SET next_retry_at=? WHERE id=?", LocalDateTime.now().minusSeconds(1), row.getId());
            }
        }
        assertStopped(row); dispatcher.retryPending(); verify(callback, times(3)).post(any(), any(), any());
    }

    // 【测什么】重复终态事件保持一行/一次即时发送，管理员撤销同样停止后续重试。
    // 【怎么算红】删掉唯一键重复事件处理或管理员撤销清理，行数/发送数/终止断言失败。
    @Test void duplicateEventAndAdminRevoke() throws Exception {
        var log = new ApiCallLog(); log.setApiKeyId(1L);
        when(logs.selectOne(any())).thenReturn(log);
        var event = new TaskStatusChangedEvent(7L, new TaskStatusChangedEvent.Message(
                "event-task", "SUCCESS", "file.mp4", "VIDEO", null, java.math.BigDecimal.ONE));
        when(callback.post(any(), any(), any())).thenReturn(503);
        dispatcher.onStatusChanged(event); dispatcher.onStatusChanged(event);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM webhook_delivery", Integer.class));
        verify(callback, times(1)).post(any(), any(), any());
        service.revoke(1L);
        assertStopped(deliveries.selectList(null).get(0));
        dispatcher.retryPending(); verify(callback, times(1)).post(any(), any(), any());
    }

    // 【测什么】新建保存secret且DNS在事务外，非法地址零落库；轮换后数据库只有新secret。
    // 【怎么算红】删secret生成/校验或圈DNS进事务，相应断言失败。
    @Test void createAndRotatePersistOnlyCurrentSecret() {
        var created = new TransactionTemplate(tm).execute(tx -> service.createOwned(7L, "key", "https://callback.example/hook", 7L, "127.0.0.1"));
        assertNotNull(created); assertEquals(32, created.record().getWebhookSecret().length());
        assertEquals(created.record().getWebhookSecret(), keys.selectById(created.record().getId()).getWebhookSecret());
        String replacement = service.rotateWebhookSecretOwned(created.record().getId(), 7L);
        assertNotEquals(created.record().getWebhookSecret(), replacement);
        assertEquals(replacement, keys.selectById(created.record().getId()).getWebhookSecret());
        doThrow(BusinessException.badRequest("unsafe")).when(callback).validate(any());
        assertThrows(BusinessException.class, () -> service.createOwned(7L, "bad", "http://bad", 7L, null));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM api_key", Integer.class));
    }

    private void assertStopped(WebhookDelivery row) {
        var stored = deliveries.selectById(row.getId());
        assertEquals(3, stored.getAttempts()); assertNull(stored.getNextRetryAt()); assertFalse(stored.getDelivered());
    }
}
