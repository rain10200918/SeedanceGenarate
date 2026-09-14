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
    AgentBatchRequoteApplication requotes=mock(AgentBatchRequoteApplication.class);
    TokenBucketRateLimitService rate=mock(TokenBucketRateLimitService.class);MockMvc mvc;
    String route="/api/agent/conversations/1/approval-grants/grant";
    String body="{\"clientActionId\":\"a\",\"expectedVersion\":1,\"bindingHash\":\""+"a".repeat(64)+"\",\"decision\":\"APPROVE\"}";
    @BeforeEach void setup(){when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));mvc=MockMvcBuilders.standaloneSetup(new AgentBatchController(batches,app,rate,requotes)).build();var u=new AppUser();u.setId(1L);UserContext.setUser(u);}
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
    // 【测什么】重新核价严格三字段，非法版本、额外decision或畸形路径不进入应用层。
    // 【怎么算红】恢复Jackson数字强转或接受额外授权字段，400和零调用断言必红。
    @Test void requoteContractRejectsMalformedCommands() throws Exception {
        String valid=body.replace(",\"decision\":\"APPROVE\"","");
        for(String v:new String[]{"1.5","\"1\"","0","-1","2147483648","null"})
            mvc.perform(post(route+"/requote").contentType("application/json").content(valid.replace("\"expectedVersion\":1","\"expectedVersion\":"+v))).andExpect(status().isBadRequest());
        mvc.perform(post(route+"/requote").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(route.replace("/1/","/0/")+"/requote").contentType("application/json").content(valid)).andExpect(status().isBadRequest());
        verifyNoInteractions(requotes,batches,app);
    }
    // 【测什么】新路由鉴权/限流/409/失败脱敏，成功只调用requote不调用approve。
    // 【怎么算红】转发到answer或去掉鉴权限流，HTTP状态/零approve调用断言必红。
    @Test void requoteUsesSeparateCommandAndHttpStatuses() throws Exception {
        String valid=body.replace(",\"decision\":\"APPROVE\"","");
        mvc.perform(post(route+"/requote").contentType("application/json").content(valid)).andExpect(status().isOk());
        verify(requotes).requote(eq(1L),eq(1L),eq("grant"),any());verifyNoInteractions(batches);
        doThrow(BusinessException.conflict("到分镜重新准备")).when(requotes).requote(anyLong(),anyLong(),anyString(),any());
        mvc.perform(post(route+"/requote").contentType("application/json").content(valid)).andExpect(status().isConflict());
        doThrow(new IllegalStateException("private-database-details")).when(requotes).requote(anyLong(),anyLong(),anyString(),any());
        mvc.perform(post(route+"/requote").contentType("application/json").content(valid)).andExpect(status().isInternalServerError()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-database-details"))));
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(false,1));
        mvc.perform(post(route+"/requote").contentType("application/json").content(valid)).andExpect(status().isTooManyRequests());
        UserContext.clear();mvc.perform(post(route+"/requote").contentType("application/json").content(valid)).andExpect(status().isUnauthorized());
    }
}
