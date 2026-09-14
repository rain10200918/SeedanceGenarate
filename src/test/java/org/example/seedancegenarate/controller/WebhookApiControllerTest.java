package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.*;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.ApiKeyService;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebhookApiControllerTest {
    ApiKeyService service;
    ApiKeyController controller;
    MockMvc mvc;
    ApiKey key;

    @BeforeEach void setup() {
        service = mock(ApiKeyService.class);
        controller = new ApiKeyController(service, mock(AppUserMapper.class), mock(ConcurrencyPolicy.class));
        ReflectionTestUtils.setField(controller, "maxPerUser", 50);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var user = new AppUser(); user.setId(7L); UserContext.setUser(user);
        key = new ApiKey(); key.setId(1L); key.setUserId(7L); key.setStatus("ENABLED");
        key.setWebhookSecret("one-time-secret"); key.setKeyHash("never-expose-hash");
        when(service.createOwned(anyLong(), any(), any(), any(), any()))
                .thenReturn(new ApiKeyService.CreatedApiKey("sk-plaintext", key));
        when(service.listByOwner(7L)).thenReturn(List.of(key));
    }
    @AfterEach void clear() { UserContext.clear(); }

    // 【测什么】真实HTTP创建响应带secret一次，列表无secret/hash，原明文key字段兼容。
    // 【怎么算红】漏掉secret传递或列表返回entity，JSON断言失败。
    @Test void secretOnlyInCreationResponse() throws Exception {
        mvc.perform(post("/api/api-keys").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.webhookSecret").value("one-time-secret"))
                .andExpect(jsonPath("$.data.plainKey").value("sk-plaintext"))
                .andExpect(jsonPath("$.data.apiKey.webhookSecret").doesNotExist());
        String list = mvc.perform(get("/api/api-keys")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("one-time-secret")); assertFalse(list.contains("webhookSecret"));
        assertFalse(list.contains("never-expose-hash"));
    }

    // 【测什么】路由绑定callback的null/空串及轮换，属主只能来自登录上下文。
    // 【怎么算红】路由/字段错位或改用请求owner，service实参断言失败。
    @Test void callbackAndRotateRoutesUseSessionOwner() throws Exception {
        when(service.updateCallbackOwned(eq(1L), eq(7L), any())).thenReturn(true);
        when(service.rotateWebhookSecretOwned(1L, 7L)).thenReturn("new-secret");
        mvc.perform(patch("/api/api-keys/1/callback").contentType("application/json")
                .content("{\"callbackUrl\":null,\"userId\":99}")).andExpect(status().isOk());
        verify(service).updateCallbackOwned(1L, 7L, null);
        mvc.perform(patch("/api/api-keys/1/callback").contentType("application/json")
                .content("{\"callbackUrl\":\"\"}")).andExpect(status().isOk());
        verify(service).updateCallbackOwned(1L, 7L, "");
        mvc.perform(post("/api/api-keys/1/webhook-secret/rotate"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.webhookSecret").value("new-secret"));
        verify(service).rotateWebhookSecretOwned(1L, 7L);
    }

    // 【测什么】非属主更新返回既有UI业务404；畸形JSON不进入service；无用户上下文不下发变更。
    // 【怎么算红】漏notFound/登录校验或接受畸形JSON，异常/零调用断言失败。
    @Test void rejectsMissingOwnerAndMalformedBody() throws Exception {
        assertEquals(404, assertThrows(BusinessException.class,
                () -> controller.updateCallback(99L, new UpdateApiKeyCallbackRequest(null))).getCode());
        clearInvocations(service);
        mvc.perform(patch("/api/api-keys/1/callback").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        UserContext.clear();
        assertThrows(RuntimeException.class, () -> controller.rotateWebhookSecret(1L));
        assertThrows(RuntimeException.class, () -> controller.updateCallback(1L, new UpdateApiKeyCallbackRequest(null)));
        verifyNoInteractions(service);
    }
}
