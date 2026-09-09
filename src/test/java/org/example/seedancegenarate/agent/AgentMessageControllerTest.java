package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.AgentMessageController;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.controller.GlobalExceptionHandler;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentMessageControllerTest {
    AgentApplication app=mock(AgentApplication.class);TokenBucketRateLimitService rate=mock(TokenBucketRateLimitService.class);MockMvc mvc;
    final String body="{\"clientMsgId\":\"i\",\"content\":\"分析图片\",\"imageAssetIds\":[\"123\"]}";
    @BeforeEach void setup(){when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));mvc=MockMvcBuilders.standaloneSetup(new AgentMessageController(app,rate)).setControllerAdvice(new GlobalExceptionHandler()).build();var user=new AppUser();user.setId(7L);UserContext.setUser(user);}
    @AfterEach void cleanup(){UserContext.clear();}
    // 【测什么】原消息路径202、JSON IDs与身份不变；业务校验精确HTTP4xx/503，未授权不执行。
    // 【怎么算红】删除局部业务异常映射，GlobalHandler会回200且断言失败。
    @Test void retainsMessageRouteAndMapsBusinessErrors() throws Exception {
        mvc.perform(post("/api/agent/conversations/12/messages").contentType("application/json").content(body)).andExpect(status().isAccepted());
        verify(app).send(eq(7L),eq(12L),argThat(r->r.imageAssetIds().equals(java.util.List.of("123"))));
        verify(rate).tryAcquireDistributed(eq("agent:command:7"),any());
        for(int code:new int[]{400,403,409,413,422,429,503}) {
            doThrow(new BusinessException(code,"请求被拒绝")).when(app).send(anyLong(),anyLong(),any());
            mvc.perform(post("/api/agent/conversations/12/messages").contentType("application/json").content(body)).andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
        }
        UserContext.clear();mvc.perform(post("/api/agent/conversations/12/messages").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    }
    // 【测什么】缺body、畸形JSON/参数返回400；错误Content-Type返回415，不变成global200。
    // 【怎么算红】移除局部绑定handler，或consumes在选定controller前拒绝请求，错误会落全局200。
    @Test void malformedTransportIsRejectedBeforeApplication() throws Exception {
        mvc.perform(post("/api/agent/conversations/12/messages").contentType("application/json")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/conversations/12/messages").contentType("application/json").content("{" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/conversations/oops/messages").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/conversations/12/messages").contentType("text/plain").content(body)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(app);
    }
}
