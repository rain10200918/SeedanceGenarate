package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.*;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.context.UserContext;
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

class AgentLocalRepairControllerTest {
    AgentRepairApplication repairs=mock(AgentRepairApplication.class);AgentApplication app=mock(AgentApplication.class);
    TokenBucketRateLimitService rate=mock(TokenBucketRateLimitService.class);MockMvc mvc;
    String route="/api/agent/conversations/12/repairs";
    String body="{\"clientActionId\":\"repair\",\"expectedWorkspaceVersion\":8,\"failureId\":\"failure\",\"optionId\":\"option\"}";
    @BeforeEach void setup() {
        var user=new AppUser();user.setId(7L);UserContext.setUser(user);
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));
        mvc=MockMvcBuilders.standaloneSetup(new AgentRepairController(repairs,app,rate)).build();
    }
    @AfterEach void clear(){UserContext.clear();}
    // 【测什么】GET只读、POST只传选项身份并确认后读取Snapshot。
    // 【怎么算红】GET调用confirm或POST直接返回建议，调用顺序断言失败。
    @Test void routesRespectReadAndConfirmContract() throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk());verify(repairs).suggestions(7,12);verifyNoInteractions(app,rate);
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isOk());
        var order=inOrder(repairs,app);order.verify(repairs).confirm(eq(7L),eq(12L),argThat(c->c.failureId().equals("failure")&&c.optionId().equals("option")&&c.expectedWorkspaceVersion()==8));order.verify(app).snapshot(7,12);
    }
    // 【测什么】未知字段、自由规格、非安全整数和错类型均在应用调用前400。
    // 【怎么算红】取消严格JSON解析或长度限制，非法请求进入confirm。
    @Test void malformedCommandsAre400() throws Exception {
        for(String bad:new String[]{"{}","{",body.replace("8","8.5"),body.replace("8","\"8\""),body.replace("8","9007199254740992"),body.replace("8","-1"),body.replace("\"option\"","{}"),body.replace("}",",\"model\":\"freely-chosen\"}")})
            mvc.perform(post(route).contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        verifyNoInteractions(repairs,app,rate);
    }
    // 【测什么】未登录、限流及业务拒绝使用真实HTTP错误码；未知错误不泄露内部内容。
    // 【怎么算红】删除HTTP状态映射或直接透传异常，状态或安全文案断言失败。
    @Test void authenticationRateAndErrorsAreSafe() throws Exception {
        UserContext.clear();mvc.perform(get(route)).andExpect(status().isUnauthorized());verifyNoInteractions(repairs);
        var user=new AppUser();user.setId(7L);UserContext.setUser(user);
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(false,1));
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isTooManyRequests());
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));
        for(int code:new int[]{400,401,403,404,409,429}) {
            doThrow(new BusinessException(code,"拒绝")).when(repairs).confirm(anyLong(),anyLong(),any());
            mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().is(code));
        }
        doThrow(new RuntimeException("private-secret")).when(repairs).confirm(anyLong(),anyLong(),any());
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("修复请求暂未确认，请重试原请求"));verifyNoInteractions(app);
    }
}
