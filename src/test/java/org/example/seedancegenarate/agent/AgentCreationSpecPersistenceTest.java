package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCreationSpecPersistenceTest extends AgentPersistentPlanTest {
    AgentViews.Artifact specDraft(int seconds) {
        var session=store.owned(id,1,false);
        var call=store.call(store.newCall(store.turn(store.newTurn(session,"self-hosted","校园宣传片")),"plan-generation","1",json.createObjectNode()));
        var data=json.createObjectNode().put("goal","校园宣传片");
        data.putArray("constraints");data.putObject("creationSpec").put("totalDurationSeconds",seconds).put("ratio","16:9");
        data.putArray("steps").addObject().put("id","script").put("kind","SCRIPT").put("title","脚本");
        var artifact=tx.execute(t->store.recordResult(session,call,new SkillResult("PLAN","计划","目标时长与画幅",null,data,null)));
        store.status(call.turnId(),"COMPLETED",null);
        return artifact;
    }
    AgentWorkspaceApplication specWorkspace() { return new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models); }
    // 【测什么】真实Plan采用、作业和脚本持久化完整传递已确认规格，不依赖摘要内容。
    // 【怎么算红】删Plan metadata传递或只存内存，真实SCRIPT版本data规格丢失；重复采用派工也失败。
    @Test void adoptedPlanSpecificationSurvivesRuntimeAndScriptPersistence() {
        var plan=specDraft(30);
        var command=new AgentWorkspaceApplication.Command("adopt-spec",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(plan.id(),1,null));
        specWorkspace().apply(1,id,command);specWorkspace().apply(1,id,command);
        db.update("UPDATE agent_session SET summary='改成900秒 1:1' WHERE conversation_id=?",id);
        when(models.complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString())).thenReturn("{\"title\":\"校园脚本\",\"content\":\"具体正文\"}");
        when(planner.decide(any())).thenReturn(callScript());
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(new ScriptGenerationSkill(models,json))),json,mock(AgentGenerationRuntime.class),diagnostics,models);
        run();run();
        var snapshot=app.snapshot(1,id);
        assertEquals("COMPLETED",snapshot.turn().status());
        var script=snapshot.artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        assertEquals(plan.data().path("creationSpec"),script.data().path("creationSpec"));
        assertEquals(1,count("agent_plan"));assertEquals(2,count("agent_artifact_version"));
        verify(models,times(1)).complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString());
    }
    // 【测什么】旧库或异常写入的非法spec不能被真实采用入口变成运行约束。
    // 【怎么算红】移除adopt前validate，错误计划插入并派发job。
    @Test void malformedStoredSpecificationCannotBeAdopted() {
        var plan=specDraft(0);
        var command=new AgentWorkspaceApplication.Command("bad-spec",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(plan.id(),1,null));
        assertThrows(BusinessException.class,()->specWorkspace().apply(1,id,command));
        assertEquals(0,count("agent_plan"));assertEquals(0,count("job_probe"));
    }
    // 【测什么】新规格计划不能沿用旧规格成功脚本，但旧作品永久保留；同规格仍可复用。
    // 【怎么算红】删adopt规格比较，40秒计划会误标30秒旧脚本成功。
    @Test void adoptionReusesOnlyMatchingSpecificationWithoutDeletingOldArtifact() {
        adoptedPlanSpecificationSurvivesRuntimeAndScriptPersistence();
        var session=store.owned(id,1,false);
        var original=app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        for(int seconds:new int[]{30,40}) {
            var next=specDraft(seconds);
            var workspace=json.createObjectNode().put("planConfirmed",true);
            workspace.set("planRef",json.valueToTree(new AgentContext.ArtifactRef(next.id(),1,null)));
            var old=workspace.putArray("steps").addObject().put("id","script").put("kind","SCRIPT").put("status","SUCCEEDED");
            old.set("artifactRef",json.valueToTree(new AgentContext.ArtifactRef(original.id(),original.version(),null)));
            tx.executeWithoutResult(t->store.plans().adopt(session,next,workspace));
            assertEquals(seconds==30?"SUCCEEDED":"READY",db.queryForObject("SELECT s.status FROM agent_plan_step s JOIN agent_plan p ON p.id=s.plan_id WHERE p.artifact_id=?",String.class,next.id()));
        }
        assertEquals(original.data(),store.artifactVersion(session,original.id(),original.version()).data());
        assertEquals(4,count("agent_artifact_version"));
    }
}
