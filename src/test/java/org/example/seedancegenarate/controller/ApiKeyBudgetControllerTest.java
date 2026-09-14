package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyBudgetRequest;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.*;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyBudgetControllerTest {
    ApiKeyBudgetService budgets = mock(ApiKeyBudgetService.class);
    ApiKeyService keys = mock(ApiKeyService.class);
    ApiKeyBudgetController owner = new ApiKeyBudgetController(budgets, keys);
    ApiKeyBudgetAdminController admin = new ApiKeyBudgetAdminController(budgets);

    @BeforeEach void setup() {
        AppUser user = new AppUser(); user.setId(7L); user.setRole("USER"); UserContext.setUser(user);
        ApiKey key = new ApiKey(); key.setId(1L); key.setUserId(7L);
        when(keys.listByOwner(7L)).thenReturn(List.of(key));
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    // 【测什么】属主入口只操作本人Key，伪造id读取/写入均404且不调用预算。
    // 【怎么算红】删除requireOwner会进入mock budgets而不抛404。
    @Test void ownerCannotReadOrChangeForeignKey() {
        assertEquals(404, assertThrows(BusinessException.class, () -> owner.get(2L)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> owner.update(2L, new ApiKeyBudgetRequest(BigDecimal.ONE, 0L))).getCode());
        verifyNoInteractions(budgets);
        owner.update(1L, new ApiKeyBudgetRequest(BigDecimal.ONE, 0L));
        verify(budgets).setLimit(7L, 1L, BigDecimal.ONE, 0L);
    }

    // 【测什么】管理预算接口即便直接调用Controller仍需管理员角色。
    // 【怎么算红】移除requireAdmin会让普通用户读写成功。
    @Test void adminRouteEnforcesRole() {
        assertEquals(403, assertThrows(BusinessException.class, () -> admin.get(1L)).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> admin.update(1L, new ApiKeyBudgetRequest(null, 0L))).getCode());
        verifyNoInteractions(budgets);
        UserContext.getUser().setRole("ADMIN");
        admin.update(1L, new ApiKeyBudgetRequest(null, 0L));
        verify(budgets).setLimit(7L, 1L, null, 0L);
    }

    // 【测什么】缺少limit不等于显式null，缺少版本不允许覆盖当前配置。
    // 【怎么算红】删除JsonProperty(required=true)会让缺limit被解析为null。
    @Test void jsonRequiresExplicitLimitAndVersion() throws Exception {
        var mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readValue("{\"expectedVersion\":0}", ApiKeyBudgetRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"limit\":10}", ApiKeyBudgetRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue("{\"limit\":10,\"expectedVersion\":null}", ApiKeyBudgetRequest.class));
        assertNull(mapper.readValue("{\"limit\":null,\"expectedVersion\":0}", ApiKeyBudgetRequest.class).limit());
    }

    // 【测什么】Key预算耗尽对外保留独立403错误码与可理解文案，不被分类器吞掉。
    // 【怎么算红】删除预算publicMessage映射会变为笼统请求未能完成。
    @Test void externalErrorRemainsDistinctFromWalletBalance() {
        var result = ApiFailureClassifier.classify(new ApiException("API_KEY_SPENDING_LIMIT_EXCEEDED", HttpStatus.FORBIDDEN, "internal"));
        assertEquals(HttpStatus.FORBIDDEN, result.getHttpStatus());
        assertEquals("API_KEY_SPENDING_LIMIT_EXCEEDED", result.getCode());
        assertTrue(result.getMessage().contains("本月消费额度不足"));
    }

    // 【测什么】HTTP PATCH真实绑定必填字段，GET登录端使用Result包装。
    // 【怎么算红】删除必填标记或改错路由，400/200或服务调用参数断言失败。
    @Test void sessionHttpBindingAndEnvelope() throws Exception {
        var view = new org.example.seedancegenarate.dto.ApiKeyBudgetView(1L, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "2026-09",
                java.time.OffsetDateTime.parse("2026-10-01T00:00:00+08:00"), "CNY", 0L);
        when(budgets.query(7L, 1L)).thenReturn(view);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(owner, admin).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/api-keys/1/budget"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.apiKeyId").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/api-keys/1/budget")
                        .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/api-keys/1/budget")
                        .contentType("application/json").content("{\"limit\":10.25,\"expectedVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(budgets).setLimit(7L, 1L, new BigDecimal("10.25"), 0L);
    }

    // 【测什么】当前API凭证只查询自己的预算、返回原始视图且没有PATCH提额接口。
    // 【怎么算红】增加v1 PATCH映射、遗漏Key属主核验或加Result包装会让断言失败。
    @Test void currentCredentialIsReadOnlyAndBoundToOwner() throws Exception {
        var account = new ApiAccountController(mock(WalletService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(account, "budgets", budgets);
        var key = new ApiKey(); key.setId(1L); key.setUserId(7L);
        var view = new org.example.seedancegenarate.dto.ApiKeyBudgetView(1L, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "2026-09",
                java.time.OffsetDateTime.parse("2026-10-01T00:00:00+08:00"), "CNY", 0L);
        when(budgets.query(7L, 1L)).thenReturn(view);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(account).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/account/key-budget")
                        .requestAttr("api_key", key))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.apiKeyId").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/account/key-budget")
                        .requestAttr("api_key", key).contentType("application/json").content("{\"limit\":null,\"expectedVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
        key.setUserId(8L);
        var request = new org.springframework.mock.web.MockHttpServletRequest(); request.setAttribute("api_key", key);
        assertThrows(ApiException.class, () -> account.keyBudget(request));
        verify(budgets, never()).query(8L, 1L);
    }
}
