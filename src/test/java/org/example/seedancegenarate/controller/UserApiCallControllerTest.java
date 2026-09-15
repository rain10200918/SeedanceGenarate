package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.interceptor.AuthInterceptor;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserApiCallControllerTest {
    UserApiCallService service;
    UserTokenService tokens;
    MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(UserApiCallService.class);
        tokens = mock(UserTokenService.class);
        var json = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new UserApiCallController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addInterceptors(new AuthInterceptor(tokens, mock(UserActivityService.class), json)).build();
    }
    @AfterEach void clear() { UserContext.clear(); }
    void login(String role) {
        var user = new AppUser(); user.setId(7L); user.setRole(role);
        when(tokens.getUserByToken(any())).thenReturn(user);
    }

    // 【测什么】真实鉴权拦截器拦截缺失及过期会话，两入口实际401且不查询。
    // 【怎么算红】移除真实拦截器或允许空会话通过，HTTP状态或零交互断言失败。
    @Test void noSessionIsHttp401() throws Exception {
        for (String path : new String[]{"", "/summary"}) {
            mvc.perform(get("/api/user/api-calls" + path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/user/api-calls" + path).header("Authorization", "Bearer expired"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    // 【测什么】会话属主覆盖伪造userId，管理员同样只传本人，默认分页1/20。
    // 【怎么算红】从请求取属主或管理员放宽范围、改默认分页，verify失败。
    @Test void adminStillUsesSessionOwner() throws Exception {
        login("ADMIN");
        mvc.perform(get("/api/user/api-calls").param("userId", "99")).andExpect(status().isOk());
        verify(service).page(eq(7L), eq(1L), eq(20L), any());
        mvc.perform(get("/api/user/api-calls/summary").param("userId", "99")).andExpect(status().isOk());
        verify(service).summary(eq(7L), any());
        Assertions.assertNull(UserContext.getUser());
    }

    // 【测什么】类型绑定错误实际HTTP400，不被全局Result包装成200。
    // 【怎么算红】删局部绑定异常handler，状态断言失败。
    @Test void malformedBindingIsHttp400() throws Exception {
        login("USER");
        for (String[] pair : new String[][]{{"current","abc"},{"size","9223372036854775808"},
                {"apiKeyId","x"}}) {
            mvc.perform(get("/api/user/api-calls").param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    // 【测什么】业务参数失败实际400，数据库失败实际500且不回显SQL/数据哨兵。
    // 【怎么算红】删除局部500兜底、回显异常或把数据库异常转400，状态/安全白名单断言失败。
    @Test void validationAndDatabaseFailureStayDistinct() throws Exception {
        login("USER");
        when(service.summary(anyLong(), any())).thenThrow(
                org.example.seedancegenarate.exception.BusinessException.badRequest("参数错误"));
        mvc.perform(get("/api/user/api-calls/summary")).andExpect(status().isBadRequest());
        var failure = new org.springframework.dao.DataAccessResourceFailureException("SELECT key_hash FROM api_key; PRIVATE_SENTINEL");
        doThrow(failure).when(service).summary(anyLong(), any());
        doThrow(failure).when(service).page(anyLong(), anyLong(), anyLong(), any());
        for(String path : new String[]{"", "/summary"}) {
            String response = mvc.perform(get("/api/user/api-calls"+path)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                    .andExpect(jsonPath("$.data").doesNotExist()).andReturn().getResponse().getContentAsString();
            Assertions.assertFalse(response.contains("PRIVATE_SENTINEL"));
            Assertions.assertFalse(response.contains("SELECT"));
            Assertions.assertFalse(response.contains("key_hash"));
        }
    }
}
