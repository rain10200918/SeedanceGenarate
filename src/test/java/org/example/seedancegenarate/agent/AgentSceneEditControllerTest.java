package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.AgentSceneEditController;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.agent.application.AgentSceneEditApplication;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.controller.GlobalExceptionHandler;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentSceneEditControllerTest {
    final AgentSceneEditApplication edits=mock(AgentSceneEditApplication.class);
    final AgentApplication app=mock(AgentApplication.class);
    final TokenBucketRateLimitService rate=mock(TokenBucketRateLimitService.class);
    final String route="/api/agent/conversations/12/scene-edits";
    final String body="""
            {"clientActionId":"edit-1","expectedWorkspaceVersion":8,
             "reference":{"artifactId":"board-1","version":3,"sceneId":"scene-2"},
             "instruction":"  第三段改成实验室  "}
            """;
    MockMvc mvc;
    @BeforeEach void setup() {
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(true,0));
        mvc=MockMvcBuilders.standaloneSetup(new AgentSceneEditController(edits,app,rate))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user=new AppUser();user.setId(7L);UserContext.setUser(user);
    }
    @AfterEach void cleanup(){UserContext.clear();}

    // 【测什么】真实路由精确转交身份、冻结版本及原始指令，执行后返回真实 Snapshot JSON。
    // 【怎么算红】改路由、漏传 reference 任一字段或提前返回空 Snapshot 都会失败。
    @Test void forwardsExactCommandAndReturnsSnapshot() throws Exception {
        when(app.snapshot(7L,12L)).thenReturn(new AgentViews.Snapshot("12","校园宣传片",9,
                new AgentViews.State("宣传片",""),null,List.of(),List.of()));
        mvc.perform(post(route).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("12"))
                .andExpect(jsonPath("$.data.revision").value(9))
                .andExpect(jsonPath("$.data.state.goal").value("宣传片"));
        var order=inOrder(edits,app);
        order.verify(edits).apply(eq(7L),eq(12L),argThat(c->c.clientActionId().equals("edit-1")
                &&c.expectedWorkspaceVersion()==8&&c.reference().artifactId().equals("board-1")
                &&c.reference().version()==3&&c.reference().sceneId().equals("scene-2")
                &&c.instruction().equals("  第三段改成实验室  ")));
        order.verify(app).snapshot(7L,12L);
        verify(rate).tryAcquireDistributed(eq("agent:command:7"),any());
    }
    // 【测什么】未登录不能执行修改、取 Snapshot 或消耗限流，并返回 HTTP401。
    // 【怎么算红】改回 GlobalExceptionHandler 的 HTTP200 包装会失败。
    @Test void unauthenticatedRequestIs401BeforeBusinessCalls() throws Exception {
        UserContext.clear();
        mvc.perform(post(route).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(edits,app,rate);
    }
    // 【测什么】限流失败返回 HTTP429，禁止继续修改或取 Snapshot。
    // 【怎么算红】删限流分支或将 HTTP429 包装为200会失败。
    @Test void rateLimitIs429AndDoesNotApply() throws Exception {
        when(rate.tryAcquireDistributed(anyString(),any())).thenReturn(new RateLimitResult(false,1));
        mvc.perform(post(route).contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429));
        verifyNoInteractions(edits,app);
    }
    // 【测什么】应用层非法输入、归属拒绝、CAS冲突仍是精确HTTP错误，不返回成功快照。
    // 【怎么算红】删局部异常映射，真实全局handler回200，此测试失败。
    @Test void applicationRejectionsKeepHttpCodes() throws Exception {
        for(int code:new int[]{400,404,409}) {
            doThrow(new BusinessException(code,"修改请求被拒绝")).when(edits).apply(anyLong(),anyLong(),any());
            mvc.perform(post(route).contentType("application/json").content(body))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
        }
        verifyNoInteractions(app);
    }
    // 【测什么】缺体、坏JSON、非法数值绑定与路径在应用执行前返回HTTP400。
    // 【怎么算红】删绑定异常局部handler会落到Global HTTP200并失败。
    @Test void malformedBindingIs400BeforeBusinessCalls() throws Exception {
        for(String malformed:new String[]{"","{",body.replace(":8",":{}"),body.replace(":3",":[]")})
            mvc.perform(post(route).contentType("application/json").content(malformed))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/agent/conversations/not-a-number/scene-edits").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(edits,app,rate);
    }
    // 【测什么】版本必须是真正JSON整数，不能由Jackson静默截断或字符串强转。
    // 【怎么算红】恢复默认数值强转，小数版本会进入应用层，预期400失败。
    @Test void versionsRejectCoercionBeforeBusinessCalls() throws Exception {
        for(String malformed:new String[]{body.replace(":8",":8.5"),body.replace(":8",":\"8\""),
                body.replace(":8",":9223372036854775808"),body.replace(":3",":3.5"),
                body.replace(":3",":\"3\""),body.replace(":3",":2147483648"),body.replace(":3",":true")})
            mvc.perform(post(route).contentType("application/json").content(malformed))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(edits,app,rate);
    }
    // 【测什么】未知异常返回HTTP500及固定安全提示，不透传上游或内部原文。
    // 【怎么算红】删本地兜底或直接返回异常message会泄露标记并失败。
    @Test void unexpectedFailureDoesNotLeakOriginalMessage() throws Exception {
        doThrow(new RuntimeException("private-provider-secret")).when(edits).apply(anyLong(),anyLong(),any());
        mvc.perform(post(route).contentType("application/json").content(body))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("修改请求暂未确认，请重试原请求"));
        verifyNoInteractions(app);
    }
}
