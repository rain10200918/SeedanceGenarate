package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCreationSpecTest {
    final ObjectMapper json = new ObjectMapper();
    final AgentModelGateway model = mock(AgentModelGateway.class);
    JsonNode node(String value) throws Exception { return json.readTree(value); }
    AgentContext context(JsonNode plan, List<AgentContext.ArtifactContext> artifacts) {
        return new AgentContext(1L,"session","turn","local","unreliable goal 60秒 1:1","stale summary",List.of(),artifacts,0,List.of(),plan,null);
    }
    void planResponse() {
        when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"校园宣传片\",\"goal\":\"模型重述成60秒\",\"constraints\":[],\"steps\":[{\"id\":\"script\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}]}");
    }
    // 【测什么】方案规格来自结构化输入并保存真实Plan数据和可见正文，不被文字模型重述覆盖。
    // 【怎么算红】移除Plan.creationSpec保存或改从模型goal解析，30秒/16:9断言失败。
    @Test void proposedSpecificationIsSavedWithoutAskingTextModelToRepeatIt() throws Exception {
        planResponse();
        var input=node("{\"instruction\":\"校园宣传片\",\"creationSpec\":{\"totalDurationSeconds\":30,\"ratio\":\"16:9\"}}");
        var result=new PlanGenerationSkill(model,json).execute(context(null,List.of()),input);
        assertEquals(input.path("creationSpec"),result.data().path("creationSpec"));
        assertTrue(result.content().contains("30秒")); assertTrue(result.content().contains("16:9"));
        assertTrue(result.data().path("constraints").get(0).asText().contains("30秒"));
        assertTrue(result.data().path("constraints").get(0).asText().contains("16:9"));
        assertFalse(result.data().has("confirmed"));
    }
    // 【测什么】修订明确版本时只覆盖显式新字段，遗漏画幅继承原版，不读取summary猜测。
    // 【怎么算红】仅保存新input丢ratio或读goal覆盖，版本规格断言失败。
    @Test void revisionKeepsUnchangedSpecificationFields() throws Exception {
        planResponse();
        var previous=new AgentContext.ArtifactContext("plan",3,"PLAN","计划","原计划",node("{\"creationSpec\":{\"totalDurationSeconds\":30,\"ratio\":\"16:9\"}}"));
        var result=new PlanGenerationSkill(model,json).execute(context(null,List.of(previous)),node("{\"instruction\":\"改成40秒\",\"source\":{\"artifactId\":\"plan\",\"version\":3},\"creationSpec\":{\"totalDurationSeconds\":40}}"));
        assertEquals(node("{\"totalDurationSeconds\":40,\"ratio\":\"16:9\"}"),result.data().path("creationSpec"));
        assertEquals(new AgentContext.ArtifactRef("plan",3,null),result.source());
    }
    // 【测什么】脚本从已确认Plan注入规格并持久化元数据，未知的旧Plan或未采用草案不能升级成确认。
    // 【怎么算红】只靠context提示但没入request/data，或无视confirmed布尔，规格断言失败。
    @Test void scriptPersistsOnlyConfirmedPlanSpecification() throws Exception {
        when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"脚本\",\"content\":\"完整正文\"}");
        var plan=node("{\"confirmed\":true,\"data\":{\"creationSpec\":{\"totalDurationSeconds\":30,\"ratio\":\"16:9\"}}}");
        var result=new ScriptGenerationSkill(model,json).execute(context(plan,List.of()),node("{\"instruction\":\"写脚本\"}"));
        assertEquals(plan.path("data").path("creationSpec"),result.data().path("creationSpec"));
        var request=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).complete(any(),eq("AGENT_SCRIPT"),anyString(),request.capture());
        assertEquals(result.data().path("creationSpec"),node(request.getValue()).path("creationSpec"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan).put("confirmed",false);
        var draft=new ScriptGenerationSkill(model,json).execute(context(plan,List.of()),node("{\"instruction\":\"写脚本\"}"));
        assertTrue(draft.data()==null || !draft.data().has("creationSpec"));
    }
    // 【测什么】脚本规格元数据与原Research引用并存，不因新字段丢失证据。
    // 【怎么算红】直接用spec覆盖metadata，citations或researchSource断言失败。
    @Test void scriptSpecificationDoesNotOverwriteResearchCitations() throws Exception {
        when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"脚本\",\"content\":\"公开资料[s1]\",\"citations\":[\"s1\"]}");
        var research=new AgentContext.ArtifactContext("research",1,"WEB_RESEARCH","资料","公开摘要",node("{\"sources\":[{\"sourceId\":\"s1\",\"snippet\":\"公开资料\"}]}"));
        var plan=node("{\"confirmed\":true,\"data\":{\"creationSpec\":{\"totalDurationSeconds\":30}}}");
        var result=new ScriptGenerationSkill(model,json).execute(context(plan,List.of(research)),node("{\"instruction\":\"写脚本\",\"source\":{\"artifactId\":\"research\",\"version\":1}}"));
        assertEquals(node("[\"s1\"]"),result.data().path("citations"));
        assertEquals("research",result.data().path("researchSource").path("artifactId").asText());
        assertEquals(30,result.data().path("creationSpec").path("totalDurationSeconds").asInt());
    }
    // 【测什么】来源脚本与已确认计划规格冲突时停止，不静默以任一侧覆盖用户采用的事实。
    // 【怎么算红】删resolve冲突校验，execute不再抛4xx且会调用模型。
    @Test void conflictingSourceSpecificationFailsBeforeModel() throws Exception {
        var plan=node("{\"confirmed\":true,\"data\":{\"creationSpec\":{\"totalDurationSeconds\":30,\"ratio\":\"16:9\"}}}");
        var source=new AgentContext.ArtifactContext("script",1,"SCRIPT","脚本","已保存正文",node("{\"creationSpec\":{\"totalDurationSeconds\":20}}"));
        assertThrows(BusinessException.class,()->new ScriptGenerationSkill(model,json).execute(context(plan,List.of(source)),node("{\"instruction\":\"改稿\",\"source\":{\"artifactId\":\"script\",\"version\":1}}")));
        verifyNoInteractions(model);
    }
    // 【测什么】新字段闭合并检查正整数总时长及比例；越界不是模型输出失败且不触发LLM。
    // 【怎么算红】去掉spec输入校验时非法spec进入模型或被忽略，assertThrows/zero调用失败。
    @ParameterizedTest @ValueSource(strings={"{\"totalDurationSeconds\":0}","{\"totalDurationSeconds\":1441}","{\"totalDurationSeconds\":1.5}","{\"ratio\":\"16/9\"}","{\"ratio\":\"0:9\"}","{\"duration\":30}"})
    void malformedSpecificationIsRejectedBeforeModel(String spec) throws Exception {
        var input=json.createObjectNode().put("instruction","生成计划");input.set("creationSpec",node(spec));
        assertThrows(BusinessException.class,()->new PlanGenerationSkill(model,json).execute(context(null,List.of()),input));
        verifyNoInteractions(model);
    }
    // 【测什么】旧计划省略规格时不从goal和summary猜30/60秒或默认画幅。
    // 【怎么算红】添加关键词提取或默认值回填时，data出现creationSpec导致断言失败。
    @Test void legacyPlanWithoutSpecificationRemainsUnknown() throws Exception {
        planResponse();var result=new PlanGenerationSkill(model,json).execute(context(null,List.of()),node("{\"instruction\":\"做30秒影片16:9\"}"));
        assertFalse(result.data().has("creationSpec"));
        assertTrue(result.content().contains("未指定"));
        assertTrue(result.data().path("constraints").get(0).asText().contains("未指定"));
    }
}
