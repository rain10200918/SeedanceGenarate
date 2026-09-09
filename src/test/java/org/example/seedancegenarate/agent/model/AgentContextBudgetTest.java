package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.example.seedancegenarate.service.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentContextBudgetTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentModelCallConfig config=new AgentModelCallConfig();
    AgentContext context(){return new AgentContext(1L,"s","t","test","goal","summary",List.of(),List.of(),0);}

    // 【测什么】实际Gateway在调用前将Schema与输出预留计入显式窗口，不向模型发送超预算请求。
    // 【怎么算红】去掉Gateway预算接线会触发模型调用，而不是CONTEXT_BUILD_FAILED。
    @Test void gatewayRefusesConfiguredWindowBeforeSending() {
        config.setContextWindows(Map.of("test",4096));
        var registry=mock(LlmChannelRegistry.class);var client=mock(LlmChatClient.class);var planner=mock(LangChain4jPlannerClient.class);
        when(registry.findRoutableStrict("test")).thenReturn(new LlmChannelSpec("test","http://local","secret","model",null,1500,LlmChannelSpec.TokenParam.MAX_TOKENS,3000,1,true,false,null));
        when(planner.chat(any(),anyList(),any(),any())).thenReturn(new LlmChatResponse("ok",1,1));
        var gateway=new AgentModelGateway(registry,client,json,config,planner);
        assertEquals("CONTEXT_BUILD_FAILED",assertThrows(LlmChannelException.class,()->gateway.completeDecision(context(),"policy","{}",json.createObjectNode())).code());
        verifyNoInteractions(client,planner);
    }

    // 【测什么】当前步与依赖完整、其他步只摘要，持久计划不修改，未来步骤再多也不注入全部正文。
    // 【怎么算红】直接注入plan会保留旧幕大正文且超预算；误删依赖或约束断言失败。
    @Test void projectsPlanWithoutDroppingCurrentDependenciesAndConstraints() throws Exception {
        var plan=json.createObjectNode().put("confirmed",true).put("currentStepId","s2");
        plan.putObject("data").put("constraints","角色服装必须一致").putArray("steps").add("duplicate");
        var steps=plan.putArray("steps");
        steps.addObject().put("id","s1").put("status","SUCCEEDED").put("detail","dependency");
        steps.addObject().put("id","s2").put("status","RUNNING").put("detail","current").putArray("dependsOn").add("s1");
        for(int i=3;i<100;i++)steps.addObject().put("id","s"+i).put("status","PENDING").put("detail","旧正文".repeat(300));
        var c=new AgentContext(1L,"s","t","test","goal","summary",List.of(),List.of(),1,List.of("确认条件"),plan,null);
        var result=new AgentContextBudget(json,config).build(c,"{}","policy",null,4096);
        var sent=json.readTree(result.content());
        assertEquals("current",sent.path("creationPlan").path("steps").get(1).path("detail").asText());
        assertEquals("dependency",sent.path("creationPlan").path("steps").get(0).path("detail").asText());
        assertTrue(sent.toString().contains("角色服装必须一致"));assertFalse(sent.toString().contains("旧正文"));
        assertTrue(plan.path("steps").get(2).has("detail"));assertTrue(sent.toString().contains("确认条件"));
    }

    // 【测什么】unknown窗口不臆测，图片与Schema成本明确计算；必需源正文不可被截断后偷发。
    // 【怎么算红】不计schema/image或自动截断request会使预算拒绝断言失败。
    @Test void reservesSchemaImagesAndRejectsIndivisibleRequest() {
        var budget=new AgentContextBudget(json,config);
        var normal=budget.build(context(),"{}","p",null,4096);
        assertNull(normal.contextWindow());
        var image=budget.build(context().withImageAssetIds(List.of("img")),"{}","p",null,4096);
        assertEquals(4096,image.sections().get("imageReserve"));assertTrue(image.estimatedTokens()>normal.estimatedTokens());
        config.setMaxInputTokens(2048);
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->budget.build(context(),"{\"sourceDocument\":\""+"中".repeat(4000)+"\"}","p",null,4096));
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->budget.build(context(),"{}","p",json.createObjectNode().put("schema","中".repeat(4000)),4096));
    }

    // 【测什么】相关作品优先于无关旧作品，最新消息与持久摘要保留；配置极值拒绝。
    // 【怎么算红】按原列表截20条会丢最后一项selected作品；删配置校验会接受0。
    @Test void prioritizesExactReferenceAndValidatesConfiguration() {
        var artifacts=new ArrayList<AgentContext.ArtifactContext>();
        for(int i=0;i<30;i++)artifacts.add(new AgentContext.ArtifactContext("a"+i,1,"SCRIPT","title","body"));
        var c=new AgentContext(1L,"s","t","test","goal","saved summary",List.of(new AgentContext.HistoryMessage("USER","latest")),artifacts,1,List.of(),null,new AgentContext.ArtifactRef("a29",1,null));
        var result=new AgentContextBudget(json,config).build(c,"{}","p",null,4096);
        assertTrue(result.content().contains("a29"));assertTrue(result.content().contains("latest"));assertTrue(result.content().contains("saved summary"));
        assertThrows(IllegalArgumentException.class,()->config.setContextWindows(Map.of("test",0)));
        assertThrows(IllegalArgumentException.class,()->config.setMaxInputTokens(0));
    }

    // 【测什么】既有观察按倒序查询，必须保留最新修正；已配置窗口的成功请求含输出/安全余量后仍在界内。
    // 【怎么算红】把get(0)改成最后一项会保留过时错误；窗口扣除缺失会违反总量约束。
    @Test void keepsNewestObservationAndBoundedTotal() {
        config.setContextWindows(Map.of("test",8192));
        var ordered=json.createArrayNode().add(json.createObjectNode().put("detail","latest correction"))
                .add(json.createObjectNode().put("detail","stale correction"));
        var c=new AgentContext(1L,"s","t","test","goal","summary",List.of(),List.of(),2,List.of(),null,null,ordered);
        var result=new AgentContextBudget(json,config).build(c,"{}","policy",null,4096);
        assertTrue(result.content().contains("latest correction"));assertFalse(result.content().contains("stale correction"));
        assertTrue(result.estimatedTokens()+result.outputReserve()<=result.contextWindow());
    }

    // 【测什么】模型后续回复和大量作品不能挤掉最后一条用户实际要求。
    // 【怎么算红】仅强制last message而非最后USER时，低预算会把用户条件丢掉。
    @Test void latestUserSurvivesAssistantMessagesAndArtifacts() {
        config.setMaxInputTokens(2048);
        var c=new AgentContext(1L,"s","t","test","goal","summary",List.of(
                new AgentContext.HistoryMessage("USER","只修改第二幕不要改变其他幕"),
                new AgentContext.HistoryMessage("ASSISTANT","回复".repeat(1500)),
                new AgentContext.HistoryMessage("ASSISTANT","回复".repeat(1500))),
                List.of(new AgentContext.ArtifactContext("a",1,"SCRIPT","title","中".repeat(16000))),2);
        var result=new AgentContextBudget(json,config).build(c,"{}","policy",null,4096);
        assertTrue(result.content().contains("只修改第二幕不要改变其他幕"));
        assertTrue(result.estimatedTokens()<=2048);
    }
}
