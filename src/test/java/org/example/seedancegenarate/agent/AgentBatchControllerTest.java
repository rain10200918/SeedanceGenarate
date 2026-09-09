package org.example.seedancegenarate.agent;
import org.example.seedancegenarate.agent.api.AgentBatchController;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AgentBatchControllerTest {
    AgentBatchApplication batches=mock(AgentBatchApplication.class);AgentApplication app=mock(AgentApplication.class);
    TokenBucketRateLimitService rate=mock(TokenBucketRateLimitService.class);MockMvc mvc;
    String route="/api/agent/conversations/1/approval-grants/grant";
    String body="{\"clientActionId\":\"a\",\"expectedVersion\":1,\"bindingHash\":\""+"a".repeat(64)+"\",\"decision\":\"APPROVE\"}";
    @BeforeEach void setup(){when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));mvc=MockMvcBuilders.standaloneSetup(new AgentBatchController(batches,app,rate)).build();var u=new AppUser();u.setId(1L);UserContext.setUser(u);}
    @AfterEach void clear(){UserContext.clear();}
    // 【测什么】严格整数及超long路径拒绝，不能静默截断版本。
    // 【怎么算红】浮点/字符串被coerce后到业务层即红。
    @Test void rejectsCoercion() throws Exception {
        for(String value:new String[]{"1.5","\"1\"","2147483648","null"})mvc.perform(post(route).contentType("application/json").content(body.replace("\"expectedVersion\":1","\"expectedVersion\":"+value))).andExpect(status().isBadRequest());
        mvc.perform(post(route.replace("/1/","/9223372036854775808/")).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(batches,app);
    }
    // 【测什么】未登录401、业务冲突409、限流429真实HTTP。
    // 【怎么算红】全局包装200或绕鉴权会失败。
    @Test void realStatuses() throws Exception {
        doThrow(BusinessException.conflict("已更新")).when(batches).answer(anyLong(),anyLong(),anyString(),any());
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isConflict());
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(false,1));
        mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isTooManyRequests());
        UserContext.clear();mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    }
}
