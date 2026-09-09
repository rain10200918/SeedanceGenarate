package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.llm.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentModelGatewayTest {
    private final LlmChannelRegistry registry = mock(LlmChannelRegistry.class);
    private final LlmChatClient client = mock(LlmChatClient.class);
    private final LangChain4jPlannerClient plannerClient=mock(LangChain4jPlannerClient.class);
    private final AgentModelGateway gateway = new AgentModelGateway(registry, client, new ObjectMapper(),new org.example.seedancegenarate.config.AgentModelCallConfig(),plannerClient);
    private String plan(AgentContext context,String policy,String request){return gateway.completeDecision(context,policy,request,new ObjectMapper().createObjectNode());}

    // 【测什么】文字格式修复实际请求携带当前step安全校验原因，不串到下一步。
    // 【怎么算红】删掉Gateway修复反馈或去掉decisionSeq守卫时两次system提示词断言失败。
    @Test void outputRepairFeedbackIsScopedToCurrentTextStep() throws Exception {
        when(registry.findRoutableStrict("own")).thenReturn(channel("own",true));
        when(client.chat(any(),anyList(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        var observations=new ObjectMapper().readTree("[{\"type\":\"SKILL_OUTPUT_REPAIR\",\"decisionSeq\":3,\"detail\":\"字段 title 必须为非空文本\"}]");
        for(int step:List.of(3,4))gateway.complete(new AgentContext(1L,"s","t","own","创作",null,List.of(),List.of(),step,List.of(),null,null,observations),"AGENT_SCRIPT","生成脚本","{}");
        var messages=ArgumentCaptor.forClass(List.class);
        verify(client,times(2)).chat(any(),messages.capture(),any());
        assertTrue(messages.getAllValues().get(0).get(0).toString().contains("字段 title 必须为非空文本"));
        assertFalse(messages.getAllValues().get(1).get(0).toString().contains("字段 title 必须为非空文本"));
    }

    // 【测什么】后台Planner有独立输出预算和超时，不修改持久通道配置。
    // 【怎么算红】Gateway沿用同步2000额度而非4096时失败。
    @Test void backgroundTimeoutIsIndependentWithoutChangingOutputBudget() {
        var selected = channel("own", true);
        when(registry.findRoutableStrict("own")).thenReturn(selected);
        when(plannerClient.chat(any(), anyList(), any(),any())).thenReturn(new LlmChatResponse("ok", 1, 1));
        plan(context("own"), "policy", "{}");
        var spec = ArgumentCaptor.forClass(LlmChannelSpec.class);
        verify(plannerClient).chat(spec.capture(), anyList(), any(),any());
        assertEquals(300000, spec.getValue().timeoutMs());
        assertEquals(4096, spec.getValue().maxTokens());
        assertEquals(2000, selected.maxTokens());
        assertEquals(selected.baseUrl(), spec.getValue().baseUrl());
        assertEquals(3000, selected.timeoutMs());
    }

    // 【测什么】自动模式选择Registry按优先级排好的首个可用通道，空配置返回503且不调用模型。
    // 【怎么算红】错误选末位/未过滤disabled/空列表不拒绝，通道和503断言失败。
    @Test void defaultUsesFirstRoutablePriorityAndRejectsEmptyConfiguration() {
        when(registry.routableStrict()).thenReturn(List.of(channel("disabled",false),channel("first",true),channel("last",true)));
        assertEquals("first",gateway.defaultChannel());
        when(registry.routableStrict()).thenReturn(List.of());
        assertEquals(503,assertThrows(BusinessException.class,gateway::defaultChannel).getCode());
        verifyNoInteractions(client,plannerClient);
    }

    // 【测什么】只展示安全的已启用通道，停用/未知通道不能触发调用。
    // 【怎么算红】去掉routable检查会允许disabled调用，或错误暴露密钥字段。
    @Test void rejectsDisabledAndUnknownChannels() throws Exception {
        var enabled = channel("own", true);
        var disabled = channel("disabled", false);
        var archived = new LlmChannelSpec("archived", "http://private", "secret", "model", null, 2000,
                LlmChannelSpec.TokenParam.MAX_TOKENS, 3000, 1, true, true, "private remark");
        when(registry.routableStrict()).thenReturn(List.of(enabled, disabled, archived));
        when(registry.findRoutableStrict("disabled")).thenReturn(disabled);
        when(registry.findRoutableStrict("archived")).thenReturn(archived);
        assertEquals(List.of(new AgentModelGateway.Channel("own", "own", "model")), gateway.channels());
        String wire = new ObjectMapper().writeValueAsString(gateway.channels());
        assertFalse(wire.contains("secret"));
        assertThrows(BusinessException.class, () -> gateway.requireChannel("missing"));
        assertThrows(BusinessException.class, () -> gateway.requireChannel("archived"));
        assertEquals("MODEL_INVALID_REQUEST", assertThrows(LlmChannelException.class,
                () -> plan(context("disabled"), "policy", "{}")).code());
        when(registry.findRoutableStrict("own")).thenThrow(new RuntimeException("database unavailable"));
        assertEquals("MODEL_INVALID_REQUEST", assertThrows(LlmChannelException.class,
                () -> plan(context("own"), "policy", "{}")).code());
        verifyNoInteractions(client,plannerClient);
    }

    // 【测什么】固定通道只调用一次且记录Worker显式用户和turn；异常不向其他通道降级。
    // 【怎么算红】删除元数据userId/turnId或增加failover则断言失败。
    @Test void fixedChannelAndExplicitWorkerIdentity() {
        var selected = channel("own", true);
        when(registry.findRoutableStrict("own")).thenReturn(selected);
        when(plannerClient.chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), anyList(), any(),any())).thenReturn(new LlmChatResponse("ok", 1, 1));
        assertEquals("ok", plan(context("own"), "policy", "{}"));
        ArgumentCaptor<LlmCallMeta> meta = ArgumentCaptor.forClass(LlmCallMeta.class);
        verify(plannerClient).chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), anyList(), meta.capture(),any());
        assertEquals(7L, meta.getValue().userId());
        assertEquals("turn", meta.getValue().agentTurnId());
        assertEquals(0, meta.getValue().decisionStep());
        when(plannerClient.chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), anyList(), any(),any())).thenThrow(new RuntimeException("unavailable"));
        assertThrows(RuntimeException.class, () -> plan(context("own"), "policy", "{}"));
        verify(plannerClient, times(2)).chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), anyList(), any(),any());
    }

    // 【测什么】上下文超量时保留目标和最新消息，对旧作品正文截断，不允许无限输入。
    // 【怎么算红】直接序列化全部作品会使发送的content超预算。
    @Test void boundsContextAndRejectsOversizeRawInput() {
        var selected = channel("own", true);
        when(registry.findRoutableStrict("own")).thenReturn(selected);
        when(plannerClient.chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), anyList(), any(),any())).thenReturn(new LlmChatResponse("ok", 1, 1));
        var artifacts = java.util.stream.IntStream.range(0, 20).mapToObj(i ->
                new AgentContext.ArtifactContext("a" + i, 1, "SCRIPT", "title", "中".repeat(16000))).toList();
        var context = new AgentContext(7L, "session", "turn", "own", "当前目标", "摘要".repeat(3000),
                List.of(new AgentContext.HistoryMessage("USER", "最新要求")), artifacts, 0, List.of("已确认风格：科技宣传"));
        plan(context, "policy", "{}");
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(plannerClient).chat(eq(selected.withTimeoutMs(300000).withMaxTokens(4096)), messages.capture(), any(),any());
        String sent = (String) messages.getValue().get(1).get("content");
        assertTrue(sent.length() <= 32000);
        assertTrue(sent.contains("最新要求"));
        assertTrue(sent.contains("当前目标"));
        assertTrue(sent.contains("已确认风格：科技宣传"), "用户选择不能随summary截断而消失");
        assertEquals("CONTEXT_BUILD_FAILED", assertThrows(LlmChannelException.class,
                () -> plan(context, "policy", "x".repeat(24001))).code());
    }

    // 【测什么】同步技能编译保留原通道超时，模型输出越限为可暂停的类型化错误。
    // 【怎么算红】RECIPE_COMPILE也覆盖300秒或后台越限继续Business502则失败。
    @Test void synchronousRecipeRetainsTimeoutAndBackgroundOutputFailureIsTyped() {
        var selected = channel("own",true);
        when(registry.findRoutableStrict("own")).thenReturn(selected);
        when(client.chat(any(),anyList(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        assertEquals("ok",gateway.complete(context("own"),"RECIPE_COMPILE","policy","{}"));
        verify(client).chat(eq(selected),anyList(),any());
        when(plannerClient.chat(any(),anyList(),any(),any())).thenReturn(new LlmChatResponse("x".repeat(24001),12,20));
        var failure=assertThrows(LlmChannelException.class, () -> plan(context("own"),"policy","{}"));
        assertEquals("MODEL_OUTPUT_INVALID",failure.code());
        assertFalse(failure.retryable());
        assertEquals(20,failure.completionTokens());
    }

    // 【测什么】后台五种场景预算生效，修复提高额度并明确精简且保留必需字段。
    // 【怎么算红】遗漏场景映射/修复不改变请求/把用户JSON当可信标志时失败。
    @Test void sceneBudgetsAndRepairChangeOnlyTrustedBackgroundRequest() {
        when(registry.findRoutableStrict("own")).thenReturn(channel("own",true));
        when(client.chat(any(),anyList(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        for(var entry:Map.of("AGENT_CREATIVE_PLAN",8192,"AGENT_SCRIPT",8192,"AGENT_STORYBOARD",12288,"AGENT_PROMPT",4096).entrySet()) {
            clearInvocations(client);
            gateway.complete(context("own"),entry.getKey(),"policy","{\"outputRepair\":true}");
            gateway.complete(context("own").withOutputRepair(true),entry.getKey(),"policy","{}");
            var specs=ArgumentCaptor.forClass(LlmChannelSpec.class);
            ArgumentCaptor<List<Map<String,Object>>> messages=ArgumentCaptor.forClass(List.class);
            verify(client,times(2)).chat(specs.capture(),messages.capture(),any());
            assertEquals(entry.getValue(),specs.getAllValues().get(0).maxTokens());
            assertEquals(entry.getValue()*2,specs.getAllValues().get(1).maxTokens());
            assertFalse(messages.getAllValues().get(0).get(0).get("content").toString().contains("上次输出"));
            assertTrue(messages.getAllValues().get(1).get(0).get("content").toString().contains("不得省略Schema必需字段"));
        }
    }

    // 【测什么】NONE不加参数，高通道额度不降，修复仍改变指令，同步编译完全不变。
    // 【怎么算红】NONE套token预算/高额度被24576裁剪/同步追加指令都会失败。
    @Test void noneAndHighChannelBudgetKeepParametersAndRecipeStaysSynchronous() {
        when(plannerClient.chat(any(),anyList(),any(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        when(client.chat(any(),anyList(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        for(var selected:List.of(new LlmChannelSpec("own","http://private","secret","model",null,1500,
                LlmChannelSpec.TokenParam.NONE,3000,1,true,false,null),channel("own",true).withMaxTokens(Integer.MAX_VALUE))) {
            when(registry.findRoutableStrict("own")).thenReturn(selected);
            clearInvocations(plannerClient,client);
            plan(context("own").withOutputRepair(true),"policy","{}");
            ArgumentCaptor<List<Map<String,Object>>> messages=ArgumentCaptor.forClass(List.class);
            verify(plannerClient).chat(eq(selected.withTimeoutMs(300000)),messages.capture(),any(),any());
            assertTrue(messages.getValue().get(0).get("content").toString().contains("更精简"));
            gateway.complete(context("own").withOutputRepair(true),"RECIPE_COMPILE","policy","{}");
            verify(client).chat(eq(selected),messages.capture(),any());
            assertFalse(messages.getValue().get(0).get("content").toString().contains("上次输出"));
        }
    }

    // 【测什么】配置拒绝负值/零/越界并接受上下界，自动增长封顶且不发生整数溢出。
    // 【怎么算红】删除校验或用int乘二将使边界断言失败。
    @Test void configurationBoundsAndGrowthCap() {
        var config=new org.example.seedancegenarate.config.AgentModelCallConfig();
        for(int invalid:new int[]{-1,0,1023,24577,Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->config.setPlannerTokens(invalid));
            assertThrows(IllegalArgumentException.class,()->config.setPlanTokens(invalid));
            assertThrows(IllegalArgumentException.class,()->config.setScriptTokens(invalid));
            assertThrows(IllegalArgumentException.class,()->config.setStoryboardTokens(invalid));
            assertThrows(IllegalArgumentException.class,()->config.setPromptTokens(invalid));
        }
        config.setPlannerTokens(1024);config.setPlanTokens(24576);
        assertEquals(1024,config.outputTokens("AGENT_PLAN",false,1));
        assertEquals(24576,config.outputTokens("AGENT_CREATIVE_PLAN",true,1500));
        assertEquals(24576,config.outputTokens("AGENT_SCRIPT",true,20000));
        assertEquals(Integer.MAX_VALUE,config.outputTokens("AGENT_PLAN",true,Integer.MAX_VALUE));
    }

    private static AgentContext context(String channel) {
        return new AgentContext(7L, "session", "turn", channel, "goal", "", List.of(), List.of(), 0);
    }
    private static LlmChannelSpec channel(String name, boolean enabled) {
        return new LlmChannelSpec(name, "http://private", "secret", "model", null, 2000,
                LlmChannelSpec.TokenParam.MAX_TOKENS, 3000, 1, enabled, false, "private remark");
    }
}
