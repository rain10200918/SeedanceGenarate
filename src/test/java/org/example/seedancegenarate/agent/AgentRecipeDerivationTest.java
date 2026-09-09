package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.recipe.*;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRecipeDerivationTest extends AgentRecipeRunTest {
    // 【测什么】真实漫剧模板连续SCRIPT阶段以显式/继承引用创建独立剧本，再自动产出分镜。
    // 【怎么算红】删除Recipe派生例外或让TextSkillSupport按同类型修订，第二步被拒绝或第一作品出现V2。
    @ParameterizedTest @ValueSource(booleans={false,true})
    void comicTemplateDerivesIndependentScriptAndContinues(boolean explicit) throws Exception {
        var accepted=app.send(1,id,send("start","做20秒小动物漫剧"));seedRun(accepted.turn().id());
        var template=new RecipeTemplates(json).get("comic-director",mock(RecipeCompiler.class));
        db.update("UPDATE creative_recipe_version SET definition_json=?",json.writeValueAsString(template.definition()));
        var s=store.owned(id,1,false);
        var data=json.createObjectNode().put("goal","做20秒小动物漫剧");data.putArray("constraints");
        var steps=data.putArray("steps");int n=0;
        for(var stage:template.definition().stages())steps.addObject().put("id","s"+(++n)).put("kind",stage.outputType()).put("title",stage.title());
        var a=store.artifact(s,"plan-fixture",null,"PLAN","漫剧计划","计划");
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),a.id());
        var w=store.workspace(s);w.set("planRef",json.createObjectNode().put("artifactId",a.id()).put("version",1));store.saveWorkspace(s,w);
        new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models).apply(1,id,
                new AgentWorkspaceApplication.Command("adopt",w.path("version").asLong(),"ADOPT_PLAN",new AgentContext.ArtifactRef(a.id(),1,null)));
        db.update("UPDATE job_probe SET done=TRUE WHERE payload NOT LIKE ?","%"+app.snapshot(1,id).turn().id()+"%");
        skills=new SkillRegistry(List.of(new ScriptGenerationSkill(models,json),new StoryboardGenerationSkill(models,json)));
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenAnswer(inv->{
            AgentContext c=inv.getArgument(0);var stage=c.recipe().path("currentStage");
            if(stage.isMissingNode())return new AgentDecision("COMPLETE","完成",null,List.of(),null,null);
            var input=json.createObjectNode().put("instruction",stage.path("instruction").asText());
            if(explicit&&stage.hasNonNull("requiresArtifactType")) {
                var refs=c.recipe().path("results").elements();com.fasterxml.jackson.databind.JsonNode ref=null;
                while(refs.hasNext())ref=refs.next();
                input.set("source",json.createObjectNode().put("artifactId",ref.path("artifactId").asText()).put("version",ref.path("version").asInt()));
            }
            return new AgentDecision("CALL_SKILL",null,null,List.of(),stage.path("allowedSkills").get(0).asText(),input);
        });
        when(models.complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString()))
                .thenReturn("{\"title\":\"角色设定\",\"content\":\"小猫戴红围巾\"}")
                .thenAnswer(inv->{var request=json.readTree((String)inv.getArgument(3));
                    assertEquals("小猫戴红围巾",request.path("sourceArtifact").path("content").asText());
                    return "{\"title\":\"完整剧本\",\"content\":\"小猫戴红围巾帮助小鸟回家\"}";});
        when(models.complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString())).thenAnswer(inv->{
            assertEquals("小猫戴红围巾帮助小鸟回家",json.readTree((String)inv.getArgument(3)).path("sourceArtifact").path("content").asText());
            return "{\"title\":\"分镜\",\"scenes\":[{\"title\":\"回家\",\"visual\":\"红围巾小猫带小鸟回家\",\"narration\":\"一起回家\",\"duration\":20}]}";});
        run();run();
        var stageOne=store.recipes().latest(store.owned(id,1,false)).results().path(template.definition().stages().get(0).id());
        var exactInput=json.createObjectNode().put("instruction","从角色设定创作剧本");
        exactInput.set("source",json.createObjectNode().put("artifactId",stageOne.path("artifactId").asText()).put("version",1));
        var current=store.turn(app.snapshot(1,id).turn().id());
        // 【测什么】派生例外只属于当前Recipe准确前置版本；普通自动计划同类型改稿仍拒绝。
        // 【怎么算红】删除Recipe精确版本检查或全局放开Plan同类型source，以下拒绝断言失败。
        var badInput=exactInput.deepCopy();badInput.withObject("source").put("version",2);
        assertThrows(InvalidAgentDecisionException.class,()->store.recipes().validateCall(store,s,current,skills.get("script-generation").descriptor(),badInput));
        assertThrows(InvalidAgentDecisionException.class,()->store.plans().validateCall(s,current,skills.get("script-generation").descriptor(),exactInput,store.workspace(s)));
        if(!explicit) {
            // Persist the state left by the former guard, then recover through the real public command twice.
            store.status(current.id(),"SUSPENDED","旧同类型限制");
            db.update("UPDATE job_probe SET done=TRUE WHERE done=FALSE");
            selectLatestScript();
            var resume=command("recover-old-run","RESUME",null,null);
            commands().command(1,id,"run",resume);commands().command(1,id,"run",resume);
            assertEquals(current.id(),app.snapshot(1,id).turn().id());
            assertEquals(1,store.recipes().latest(s).stage());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'",Integer.class));
        }
        if(!explicit)selectLatestScript();
        run();run();
        assertEquals(2,store.recipes().latest(store.owned(id,1,false)).stage(),"第二SCRIPT阶段必须产出并推进");
        if(!explicit)selectLatestScript();
        run();run(); // Final artifact deterministically completes the adopted Plan.
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals("COMPLETED",app.snapshot(1,id).state().recipeRun().path("status").asText());
        assertEquals(2,db.queryForObject("SELECT COUNT(DISTINCT artifact_id) FROM agent_artifact_version WHERE type='SCRIPT'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE version_no>1",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'",Integer.class));
        var results=store.recipes().latest(s).results();
        var second=results.path(template.definition().stages().get(1).id());
        var third=results.path(template.definition().stages().get(2).id());
        assertEquals(json.valueToTree(new AgentContext.ArtifactRef(stageOne.path("artifactId").asText(),1,null)),
                store.artifactVersion(s,second.path("artifactId").asText(),1).sourceRef());
        assertEquals(json.valueToTree(new AgentContext.ArtifactRef(second.path("artifactId").asText(),1,null)),
                store.artifactVersion(s,third.path("artifactId").asText(),1).sourceRef());
        verify(models,times(2)).complete(any(),eq("AGENT_SCRIPT"),anyString(),anyString());
        verify(models,times(1)).complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString());
    }
    private void selectLatestScript() {
        var s=store.owned(id,1,false);var w=store.workspace(s);
        var refs=store.recipes().latest(s).results().elements();com.fasterxml.jackson.databind.JsonNode ref=null;
        while(refs.hasNext())ref=refs.next();
        w.set("selection",json.createObjectNode().put("artifactId",ref.path("artifactId").asText()).put("version",ref.path("version").asInt()));
        store.saveWorkspace(s,w);
    }
}
