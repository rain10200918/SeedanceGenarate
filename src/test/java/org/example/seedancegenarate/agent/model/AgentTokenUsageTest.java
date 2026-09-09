package org.example.seedancegenarate.agent.model;

import org.aspectj.lang.ProceedingJoinPoint;
import org.example.seedancegenarate.aspect.TokenUsageAspect;
import org.example.seedancegenarate.entity.PromptTokenUsage;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.TokenUsageService;
import org.example.seedancegenarate.service.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTokenUsageTest {
    // 【测什么】新迁移让未获得usage的失败记录真正存NULL，不再被数据库默认值变成0。
    // 【怎么算红】保留旧NOT NULL DEFAULT 0定义或漏掉V41迁移，数据库读取断言失败。
    @Test void missingUsageRemainsUnknownInDatabase() throws java.io.IOException {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:usage-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        // H2不支持MySQL同一ALTER中的多个子句；仅拆语句，不改列默认值/可空性。
        String migration = new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/V41__llm_usage_diagnostics.sql").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace(",\n    ", ";\nALTER TABLE prompt_token_usage ");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V8__prompt_token_usage.sql"),
                new org.springframework.core.io.ByteArrayResource(migration.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .execute(dataSource);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO prompt_token_usage(scene,llm_model,status) VALUES('AGENT_PLAN','test','FAILED')");
        var row = jdbc.queryForMap("SELECT prompt_tokens,completion_tokens,total_tokens,usage_source FROM prompt_token_usage");
        assertNull(row.get("prompt_tokens")); assertNull(row.get("completion_tokens")); assertNull(row.get("total_tokens"));
        assertEquals("UNKNOWN", row.get("usage_source"));
    }
    // 【测什么】失败响应保留上游usage和分类；超时没有usage保持未知，不保存原始异常内容。
    // 【怎么算红】失败一律丢弃usage、总量伪造为零或记录原始reason时断言失败。
    @Test void failedUsageKeepsProviderFactsWithoutLeakingPayload() throws Throwable {
        var service = mock(TokenUsageService.class);
        var aspect = new TokenUsageAspect(service);
        var pjp = mock(ProceedingJoinPoint.class);
        var channel = new LlmChannelSpec("own", "http://private", "secret", "model", null, 100,
                LlmChannelSpec.TokenParam.MAX_TOKENS, 3000, 0, true, false, null);
        when(pjp.getArgs()).thenReturn(new Object[]{channel, List.of(), new LlmCallMeta("AGENT_PLAN", null, 7L, "turn", 3)});
        when(pjp.proceed()).thenThrow(LlmChannelException.failoverable("secret prompt payload", null)
                .classified("MODEL_OUTPUT_INVALID", false, 200, null, 1200, 1500))
                .thenThrow(LlmChannelException.readTimeout(null));
        assertThrows(LlmChannelException.class, () -> aspect.record(pjp));
        assertThrows(LlmChannelException.class, () -> aspect.record(pjp));
        var captor = ArgumentCaptor.forClass(PromptTokenUsage.class);
        verify(service, times(2)).record(captor.capture());
        var failed = captor.getAllValues().get(0);
        assertEquals(1200, failed.getPromptTokens());
        assertEquals(1500, failed.getCompletionTokens());
        assertEquals(2700, failed.getTotalTokens());
        assertEquals(3, failed.getDecisionStep());
        assertEquals("PROVIDER", failed.getUsageSource());
        assertTrue(failed.getErrorMsg().contains("MODEL_OUTPUT_INVALID"));
        assertFalse(failed.getErrorMsg().contains("secret"));
        assertNull(captor.getAllValues().get(1).getTotalTokens());
        assertEquals("UNKNOWN", captor.getAllValues().get(1).getUsageSource());
    }
    @AfterEach void clearContext() { UserContext.clear(); }
    // 【测什么】没有HTTP UserContext的Worker成功和失败都按显式user/turn记账。
    // 【怎么算红】切面仍只读ThreadLocal或者未写turn时断言失败。
    @Test void recordsWorkerIdentityOnSuccessAndFailure() throws Throwable {
        var service = mock(TokenUsageService.class);
        var aspect = new TokenUsageAspect(service);
        var pjp = mock(ProceedingJoinPoint.class);
        var channel = new LlmChannelSpec("own", "http://private", "secret", "model", null, 100,
                LlmChannelSpec.TokenParam.MAX_TOKENS, 3000, 0, true, false, null);
        when(pjp.getArgs()).thenReturn(new Object[]{channel, List.of(Map.of("role", "user", "content", "hello")),
                new LlmCallMeta("AGENT_PLAN", null, 7L, "turn-1")});
        when(pjp.proceed()).thenReturn(new LlmChatResponse("ok", 10, 2)).thenThrow(new RuntimeException("timeout"));
        aspect.record(pjp);
        assertThrows(RuntimeException.class, () -> aspect.record(pjp));
        var captor = ArgumentCaptor.forClass(PromptTokenUsage.class);
        verify(service, times(2)).record(captor.capture());
        for (var usage : captor.getAllValues()) {
            assertEquals(7L, usage.getUserId());
            assertEquals("turn-1", usage.getAgentTurnId());
        }
        assertEquals("FAILED", captor.getAllValues().get(1).getStatus());
        assertNull(new LlmCallMeta("legacy", null).userId());
    }

    // 【测什么】显式Worker用户不被线程残留用户覆盖；原两参请求仍按原上下文统计。
    // 【怎么算红】无条件读UserContext或删除旧请求兼容分支时断言失败。
    @Test void explicitIdentityOverridesStaleThreadAndLegacyStillWorks() throws Throwable {
        var service = mock(TokenUsageService.class);
        var aspect = new TokenUsageAspect(service);
        var pjp = mock(ProceedingJoinPoint.class);
        var stale = new AppUser(); stale.setId(99L); stale.setUsername("legacy"); UserContext.setUser(stale);
        var channel = new LlmChannelSpec("own", "http://private", "secret", "model", null, 100,
                LlmChannelSpec.TokenParam.MAX_TOKENS, 3000, 0, true, false, null);
        when(pjp.getArgs()).thenReturn(new Object[]{channel, List.of(), new LlmCallMeta("AGENT_PLAN", null, 7L, "turn")})
                .thenReturn(new Object[]{channel, List.of(), new LlmCallMeta("legacy", null)});
        when(pjp.proceed()).thenReturn(new LlmChatResponse("ok", 1, 1));
        aspect.record(pjp); aspect.record(pjp);
        var captor = ArgumentCaptor.forClass(PromptTokenUsage.class);
        verify(service, times(2)).record(captor.capture());
        assertEquals(7L, captor.getAllValues().get(0).getUserId());
        assertNull(captor.getAllValues().get(0).getUserName());
        assertEquals(99L, captor.getAllValues().get(1).getUserId());
        assertEquals("legacy", captor.getAllValues().get(1).getUserName());
    }
}
