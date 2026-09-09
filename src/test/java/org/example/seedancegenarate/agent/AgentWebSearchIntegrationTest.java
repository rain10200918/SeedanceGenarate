package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.skill.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.example.seedancegenarate.agent.runtime.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWebSearchIntegrationTest extends AgentPersistentPlanTest {
    SearchProvider provider;
    @BeforeEach void searchSetup() throws Exception {
        var migration=new String(getClass().getResourceAsStream("/db/migration/V50__agent_search_attempt.sql").readAllBytes());
        db.execute(migration.replaceAll("(?i)\\) ENGINE\\s*=.*?;",");"));
        provider=mock(SearchProvider.class);when(provider.available()).thenReturn(true);
        when(provider.search(any())).thenReturn(new SearchProvider.Result("fake",List.of(new SearchProvider.Source("服务","https://www.cma.gov.cn/service","提供公共气象服务"))));
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }
    void searchRuntime() {
        skills=new SkillRegistry(List.of(new WebSearchSkill(provider,json),new ScriptGenerationSkill(models,json)));
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
    }
    void startResearch() {
        adopt(List.of(Map.of("id","research","kind","WEB_RESEARCH","title","公开资料"),Map.of("id","script","kind","SCRIPT","title","脚本")));
        searchRuntime();
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"web-search",json.createObjectNode().put("query","武清 气象 公共服务")));
        run();
    }
    // 【测什么】公开资料步骤是持久Plan的一部分，不退化成聊天里人工继续。
    // 【怎么算红】PlanStore仍拒绝WEB_RESEARCH时采用计划抛异常，不能创建两步计划。
    @Test void researchPlanKindIsAccepted() {
        adopt(List.of(Map.of("id","research","kind","WEB_RESEARCH","title","公开资料"),
                Map.of("id","script","kind","SCRIPT","title","脚本")));
        assertEquals(2,count("agent_plan_step"));
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】脚本精确引用研究版本，不能把来源与正文仅留在模型记忆。
    // 【怎么算红】TextSkillSupport仍拒绝WEB_RESEARCH或不保存引用metadata时失败。
    @Test void scriptUsesResearchSource() {
        var gateway=mock(AgentModelGateway.class);
        when(gateway.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"脚本\",\"content\":\"公开服务 [s1]\",\"citations\":[\"s1\"]}");
        var data=json.createObjectNode();data.putArray("sources").addObject().put("sourceId","s1").put("snippet","公开服务");
        var ref=new AgentContext.ArtifactRef("research",1,null);
        var context=new AgentContext(1L,"session","turn","model","goal",null,List.of(),
                List.of(new AgentContext.ArtifactContext("research",1,"WEB_RESEARCH","资料","公开服务",data)),0);
        var input=json.createObjectNode().put("instruction","脚本");input.set("source",json.valueToTree(ref));
        var result=new ScriptGenerationSkill(gateway,json).execute(context,input);
        assertEquals(ref,result.source());assertEquals("s1",result.data().path("citations").get(0).asText());
    }
    // 【测什么】真实Job链搜索完成自动续脚本，准确来源持久化，成功Job重放不再搜索。
    // 【怎么算红】去掉finishSkill续跑、来源元数据或current成功Call守卫时失败。
    @Test void researchToScriptThroughDurableWorker() {
        startResearch();var searchJob=next();run();runtime.execute(searchJob,true);
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("steps").get(0).path("status").asText());
        var source=app.snapshot(1,id).artifacts().stream().filter(a->"WEB_RESEARCH".equals(a.type())).findFirst().orElseThrow();
        assertEquals("SEARCH_RESULT",source.data().path("evidence").get(0).path("status").asText());
        assertNull(source.sourceRef());assertEquals("https://www.cma.gov.cn/service",source.data().path("sources").get(0).path("url").asText());
        var ref=new AgentContext.ArtifactRef(source.id(),source.version(),null);
        var input=json.createObjectNode().put("instruction","引用来源写脚本");input.set("source",json.valueToTree(ref));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input));
        when(models.complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString())).thenReturn("{\"title\":\"脚本\",\"content\":\"公共气象服务 [s1]\",\"citations\":[\"s1\"]}");
        searchRuntime();run();run(); // new Runtime instance, original persistence and job chain
        var script=app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        assertEquals(json.valueToTree(ref),script.sourceRef());assertEquals("s1",script.data().path("citations").get(0).asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());assertEquals(0,count("agent_approval"));
        verify(provider,times(1)).search(any());
        System.out.println("WEB_SEARCH_TRACE mockPlanner=true mockProvider=true database=H2 researchId="+source.id()
                +" scriptId="+script.id()+" sourceRef="+script.sourceRef()+" turnStatus="+app.snapshot(1,id).turn().status());
    }
    // 【测什么】空结果保存不足证据，但不能成功完成研究步或进入脚本。
    // 【怎么算红】recordResult无条件advance或finishSkill继续入队时失败。
    @Test void emptyResultDoesNotAdvancePlan() {
        when(provider.search(any())).thenReturn(new SearchProvider.Result("fake",List.of()));
        startResearch();run();
        var snap=app.snapshot(1,id);assertEquals("SUSPENDED",snap.turn().status());
        assertNotEquals("SUCCEEDED",snap.state().workspace().path("steps").get(0).path("status").asText());
        assertEquals("INSUFFICIENT_EVIDENCE",snap.artifacts().stream().filter(a->"WEB_RESEARCH".equals(a.type())).findFirst().orElseThrow().data().path("status").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】429/timeout仅有界重试搜索，同一计划跨Turn不能重置3请求预算。
    // 【怎么算红】删持久scope总量检查或把搜索错归模型重试时失败。
    @Test void searchRetriesAreBoundedAcrossTurnYield() {
        when(provider.search(any())).thenThrow(new SearchProvider.Failure("SEARCH_RATE_LIMITED",true));
        startResearch();run();assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        db.update("UPDATE agent_search_attempt SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        db.update("UPDATE agent_search_attempt SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());verify(provider,times(3)).search(any());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
        var session=store.owned(id,1,false);var turn=store.turn(session.activeTurnId());
        store.status(turn.id(),"QUEUED",null); // simulate an explicitly resumed slice reaching its Decision boundary
        var nextTurn=tx.execute(t->store.yieldToSystemContinue(store.owned(id,1,true),turn)).orElseThrow();
        var call=store.call(store.newCall(nextTurn,"web-search","1.0.0",json.createObjectNode().put("query","新公开关键词")));
        var failure=assertThrows(SearchProvider.Failure.class,()->tx.execute(t->store.search().begin(store,store.owned(id,1,true),nextTurn,call,call.id())));
        assertEquals("SEARCH_BUDGET_EXHAUSTED",failure.code());
    }
    // 【测什么】搜索途中用户取消后，迟到返回不得写作品或继续旧计划。
    // 【怎么算红】finishSkill删current epoch/activeTurn守卫时落研究作品。
    @Test void cancelledSearchResultDoesNotResume() {
        startResearch();
        when(provider.search(any())).thenAnswer(a->{app.cancel(1,id,app.snapshot(1,id).turn().id());return new SearchProvider.Result("fake",List.of(new SearchProvider.Source("来源","https://cma.gov.cn/a","公开事实")));});
        run();assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        assertEquals(0,app.snapshot(1,id).artifacts().stream().filter(a->"WEB_RESEARCH".equals(a.type())).count());
    }
    // 【测什么】跨用户/伪造research引用在LLM执行前拒绝，来源不从客户端信任。
    // 【怎么算红】Runtime.resolve绕过owner准确版本查询时模型调用次数不再为零。
    @Test void unavailableResearchReferenceCannotReachScriptModel() {
        startResearch();run();
        var input=json.createObjectNode().put("instruction","脚本");input.putObject("source").put("artifactId","other-user-research").put("version",1);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input));run();
        verify(models,never()).complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString());
        assertEquals(0,app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).count());
    }
}
