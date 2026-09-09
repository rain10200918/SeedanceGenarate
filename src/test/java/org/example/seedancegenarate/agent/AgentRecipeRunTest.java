package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.recipe.*;
import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRecipeRunTest extends AgentRuntimeIntegrationTest {
    // 【测什么】尚未创建Plan的活动Recipe普通回复也必须等待用户，回复后恢复同一轮。
    // 【怎么算红】只检查Plan或继续validateComplete，RESPOND会完成或反复修正而不是WAITING_USER。
    @Test void responseInRecipeCreatesResumableInteraction() {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());
        db.update("UPDATE agent_recipe_run SET variables_json=?","{\"school\":\"辽宁\"}");
        when(planner.decide(any())).thenReturn(new AgentDecision("RESPOND","请说明影片风格",null,List.of(),null,null));
        run();assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        assertNotNull(store.pending(accepted.turn().id()));assertEquals(0,count("agent_skill_call"));
        var resumed=app.send(1,id,send("answer","科技风格"));
        assertEquals(accepted.turn().id(),resumed.turn().id());assertEquals("QUEUED",resumed.turn().status());
        assertNotEquals("COMPLETED",store.recipes().bound(store.turn(accepted.turn().id())).status());
    }
    void delayedRecovery() {
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }
    // 【测什么】第64个Recipe决策发生模型重试仍是同一决策，不耗预算也不被先行拒绝。
    // 【怎么算红】恢复beforeStep的无条件decisions>=64检查，重试后仍SUSPENDED且Planner只调用一次。
    @Test void lastBudgetedDecisionCanRetryWithoutCountingTwice() {
        delayedRecovery();var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());
        db.update("UPDATE agent_recipe_run SET variables_json='{}',decision_count=63");
        db.update("UPDATE agent_recipe_run SET variables_json=?","{\"school\":\"辽宁\"}");
        when(planner.decide(any())).thenThrow(org.example.seedancegenarate.service.llm.LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("timeout"))).thenReturn(ask());
        run();assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        assertEquals(64,db.queryForObject("SELECT decision_count FROM agent_recipe_run",Integer.class));verify(planner,times(2)).decide(any());
    }
    // 【测什么】所有Recipe阶段完成后总结模型超时可恢复，阶段不会重跑或重复改完成版本。
    // 【怎么算红】终态beforeStep每次update或恢复重新启动阶段，版本/阶段/完成状态断言失败。
    @Test void finalSummaryRetryDoesNotRerunCompletedRecipe() throws Exception {
        delayedRecovery();var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());
        var definition=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT definition_json FROM creative_recipe_version",String.class));
        definition.putArray("acceptanceRules");db.update("UPDATE creative_recipe_version SET definition_json=?",definition.toString());
        db.update("UPDATE agent_recipe_run SET stage_index=1,variables_json=?","{\"school\":\"辽宁\"}");
        when(planner.decide(any())).thenThrow(org.example.seedancegenarate.service.llm.LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("timeout")))
                .thenReturn(new AgentDecision("COMPLETE","完成",null,List.of(),null,null));
        run();long version=store.recipes().latest(store.owned(id,1,false)).version();
        assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());assertEquals(version,store.recipes().latest(store.owned(id,1,false)).version());
        assertEquals(0,count("agent_skill_call"));
    }
    void seedRun(String turn) {
        String definition="""
                {"instruction":"校园策划","requiredInputs":[],"variables":[{"id":"school","label":"学校","required":true,"options":[]}],
                "stages":[{"id":"script","title":"脚本","instruction":"校园宣传片","allowedSkills":["script-generation"],"requiredVariables":["school"],"requiresArtifactType":null,"requiresArtifactApproval":false,"outputType":"SCRIPT"}],
                "requiredCapabilities":["script-generation"],"acceptanceRules":[{"type":"ARTIFACT_EXISTS","artifactType":"SCRIPT"}]}
                """;
        db.update("INSERT INTO creative_recipe(id,owner_id,name,description,instruction) VALUES('recipe',1,'校园策划','描述','原文')");
        db.update("INSERT INTO creative_recipe_version(id,recipe_id,version,name,description,instruction,definition_json,content_hash,compiler_version) VALUES('version','recipe',1,'校园策划','描述','原文',?,'hash','1')",definition);
        db.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,turn_id,variables_json,results_json,approvals_json) VALUES('run',?,'version',?,'{}','{}','{}')",store.owned(id,1,false).id(),turn);
        db.update("UPDATE agent_session SET active_recipe_run_id='run' WHERE conversation_id=?",id);
    }
    // 【测什么】缺少Recipe必填学校时，Worker在调模型前进入真实等待，不能让LLM跳过。
    // 【怎么算红】删掉Runtime.begin中的Recipe门槛调用，Planner被执行且Turn不是WAITING_USER。
    @Test void missingVariableBlocksBeforePlanner() {
        var accepted=app.send(1,id,send("start","制作校园片")); seedRun(accepted.turn().id());
        when(planner.decide(any())).thenReturn(new AgentDecision("COMPLETE","完成",null,List.of(),null,null));
        run();
        assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        assertEquals("WAITING_INPUT",db.queryForObject("SELECT status FROM agent_recipe_run WHERE id='run'",String.class));
        verifyNoInteractions(planner);
    }
    AgentRecipeApplication commands() {return new AgentRecipeApplication(store,mock(RecipeCatalog.class),models,jobs,new AgentApprovalStore(db,store),tx);}
    AgentRecipeApplication.Command command(String key,String action,Map<String,String> values,AgentRecipeApplication.Reference ref) {
        return new AgentRecipeApplication.Command(key,store.recipes().latest(store.owned(id,1,false)).version(),action,values,ref);
    }
    void answerSchool() {commands().command(1,id,"run",command("answer","ANSWER",Map.of("school","辽宁职业技术学院"),null));}
    void boardStage(boolean approval) {
        var r=store.recipes().latest(store.owned(id,1,false));var d=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(store.recipes().definition(r));
        ((com.fasterxml.jackson.databind.node.ArrayNode)d.path("stages")).addObject().put("id","board").put("title","分镜").put("instruction","校园分镜")
                .put("requiresArtifactType","SCRIPT").put("requiresArtifactApproval",approval).put("outputType","STORYBOARD")
                .set("allowedSkills",json.valueToTree(List.of("storyboard-generation")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)d.path("stages").get(1)).set("requiredVariables",json.createArrayNode());
        ((com.fasterxml.jackson.databind.node.ArrayNode)d.path("requiredCapabilities")).add("storyboard-generation");
        db.update("UPDATE creative_recipe_version SET definition_json=? WHERE id='version'",d.toString()); // fixture setup, not a product update API
    }
    void adoptPlan(boolean board) throws Exception {
        var s=store.owned(id,1,false);var data=json.readTree(board?"{\"goal\":\"辽宁校园片\",\"constraints\":[],\"steps\":[{\"id\":\"s\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"},{\"id\":\"b\",\"kind\":\"STORYBOARD\",\"title\":\"分镜\"}]}":"{\"goal\":\"辽宁校园片\",\"constraints\":[],\"steps\":[{\"id\":\"s\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}]}");
        var a=store.artifact(s,"planfixture-"+java.util.UUID.randomUUID(),null,"PLAN","计划","具体计划");db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),a.id());
        var w=store.workspace(s);w.set("planRef",json.createObjectNode().put("artifactId",a.id()).put("version",1));store.saveWorkspace(s,w);
        new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models).apply(1,id,new AgentWorkspaceApplication.Command("adopt",w.path("version").asLong(),"ADOPT_PLAN",new AgentContext.ArtifactRef(a.id(),1,null)));
        db.update("UPDATE job_probe SET done=TRUE WHERE payload NOT LIKE ?","%"+app.snapshot(1,id).turn().id()+"%");
    }
    // 【测什么】结构化答案幂等、真实字段隔离；聊天不能伪造Recipe变量，预算跨新Turn保留。
    // 【怎么算红】去掉ANSWER字段白名单/幂等或human预算记录，变量/计数/409断言失败。
    @Test void answersAreStructuredIdempotentAndBudgetIsPersistent() {
        var accepted=app.send(1,id,send("start","制作校园片"));seedRun(accepted.turn().id());run();
        assertEquals(409,assertThrows(BusinessException.class,()->app.send(1,id,send("chat","school=已填"))).getCode());
        assertThrows(BusinessException.class,()->commands().command(1,id,"run",command("fake","ANSWER",Map.of("storyboard.approved","true"),null)));
        var command=command("answer","ANSWER",Map.of("school","辽宁职业技术学院"),null);
        commands().command(1,id,"run",command);commands().command(1,id,"run",command);
        var r=store.recipes().latest(store.owned(id,1,false));assertEquals("辽宁职业技术学院",r.variables().path("school").asText());assertEquals(1,r.interactions());
        assertEquals(accepted.turn().id(),app.snapshot(1,id).turn().id());
    }
    // 【测什么】采用Plan后脚本结果进入本Run，分镜前等待精确作品确认，确认自动恢复后真正完成。
    // 【怎么算红】删result推进/确认门槛/Run新Turn绑定或确认后的入队，阶段/状态/作品断言失败。
    @Test void adoptedPlanAdvancesAndApprovalAutomaticallyResumes() throws Exception {
        var accepted=app.send(1,id,send("start","制作校园片"));seedRun(accepted.turn().id());boardStage(true);run();answerSchool();adoptPlan(true);
        assertNotEquals(accepted.turn().id(),app.snapshot(1,id).turn().id());
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","脚本",json.createObjectNode(),"SCRIPT"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","辽宁脚本","校园介绍",null));run();run();
        var snap=app.snapshot(1,id);assertEquals("WAITING_CONFIRMATION",snap.state().recipeRun().path("status").asText());
        var artifact=snap.state().recipeRun().path("approvalArtifact");String aid=artifact.path("artifactId").asText();
        assertEquals(1,artifact.path("version").asInt());assertEquals("board",snap.state().recipeRun().path("stageId").asText());
        assertThrows(BusinessException.class,()->commands().command(1,id,"run",command("wrong","APPROVE_ARTIFACT",null,new AgentRecipeApplication.Reference(aid,2))));
        commands().command(1,id,"run",command("approve","APPROVE_ARTIFACT",null,new AgentRecipeApplication.Reference(aid,1)));
        var board=mock(CreativeSkill.class);when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD"));
        skills=new SkillRegistry(List.of(skill,board));runtime=new org.example.seedancegenarate.agent.runtime.AgentRuntime(store,jobs,tx,planner,skills,json,mock(org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime.class),diagnostics,models);
        var ref=new AgentContext.ArtifactRef(aid,1,null);var input=json.createObjectNode();input.set("source",json.valueToTree(ref));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"storyboard-generation",input));
        when(board.execute(any(),any())).thenAnswer(a->{AgentContext c=a.getArgument(0);assertEquals("辽宁职业技术学院",c.recipe().path("variables").path("school").asText());return new SkillResult("STORYBOARD","校园分镜","分镜内容",null,json.readTree("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"校门\",\"visual\":\"校门晨光\",\"narration\":\"欢迎\"}]}"),ref);});
        run();run();
        assertEquals("COMPLETED",app.snapshot(1,id).state().recipeRun().path("status").asText());assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals(2,store.recipes().latest(store.owned(id,1,false)).results().size());verify(board,times(1)).execute(any(),any());
    }
    // 【测什么】全部Plan作品齐备也必须等Recipe最后准确版本确认，批准后无模型收尾且只交付一次。
    // 【怎么算红】completePlanIfReady跳过Recipe.beforeStep门槛，未批准时将COMPLETED而非WAITING_USER。
    @Test void finalRecipeApprovalIsNotBypassedByCompletePlanSteps() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());run();answerSchool();adoptPlan(false);
        var definition=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT definition_json FROM creative_recipe_version",String.class));
        var gate=((com.fasterxml.jackson.databind.node.ArrayNode)definition.path("stages")).addObject().put("id","accept").put("title","验收脚本").put("instruction","确认最终脚本")
                .put("requiresArtifactType","SCRIPT").put("requiresArtifactApproval",true).putNull("outputType");
        gate.putArray("allowedSkills");gate.putArray("requiredVariables");
        db.update("UPDATE creative_recipe_version SET definition_json=?",definition.toString());
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","脚本",json.createObjectNode(),"SCRIPT"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","校园脚本","完整脚本",null));run();run();
        var snap=app.snapshot(1,id);assertEquals("WAITING_USER",snap.turn().status());
        assertEquals("WAITING_CONFIRMATION",snap.state().recipeRun().path("status").asText());
        assertNotEquals("SUCCEEDED",db.queryForObject("SELECT status FROM agent_plan",String.class));
        var a=snap.state().recipeRun().path("approvalArtifact");
        var command=command("final-approve","APPROVE_ARTIFACT",null,new AgentRecipeApplication.Reference(a.path("artifactId").asText(),a.path("version").asInt()));
        commands().command(1,id,"run",command);commands().command(1,id,"run",command);run();
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM agent_plan",String.class));assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        verify(planner,times(1)).decide(any());verify(skill,times(1)).execute(any(),any());
    }
    // 【测什么】开始运行固定发布版本与用户目标；重放不新增Run，同秒其它记录不抢active指针。
    // 【怎么算红】start取最新版本/忽略请求重放，或latest按created_at排序，版本/数量/active断言失败。
    @Test void startPinsVersionAndExplicitActivePointerWins() {
        seedRun("unused");var definition=store.recipes().definition(store.recipes().latest(store.owned(id,1,false)));
        db.update("DELETE FROM agent_recipe_run");db.update("UPDATE agent_session SET active_recipe_run_id=NULL");
        var catalog=mock(RecipeCatalog.class);when(catalog.requireRunnable(1,"version")).thenReturn(new RecipeCatalog.Published("version","recipe",1,"校园策划","描述","原文",definition,"hash",List.of()));
        var application=new AgentRecipeApplication(store,catalog,models,jobs,new AgentApprovalStore(db,store),tx);
        var request=new AgentRecipeApplication.Start("use",0L,"version","本次目标是辽宁校园片");
        application.start(1,id,request);String runId=app.snapshot(1,id).state().recipeRun().path("id").asText();application.start(1,id,request);
        assertEquals(1,count("agent_recipe_run"));assertEquals(1,count("agent_turn"));assertEquals(1,count("job_probe"));
        db.update("INSERT INTO creative_recipe_version(id,recipe_id,version,name,description,instruction,definition_json,content_hash,compiler_version) SELECT 'version2',recipe_id,2,'新版','新版','新版',definition_json,'new','1' FROM creative_recipe_version WHERE id='version'");
        db.update("UPDATE creative_recipe SET latest_version=2,latest_version_id='version2'");
        db.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,variables_json,results_json,approvals_json,created_at) VALUES('zzzz',?,'version2','{}','{}','{}',TIMESTAMPADD(DAY,1,NOW()))",store.owned(id,1,false).id());
        assertEquals(runId,store.recipes().latest(store.owned(id,1,false)).id());assertEquals(1,app.snapshot(1,id).state().recipeRun().path("recipeVersion").asInt());
        assertEquals(404,assertThrows(BusinessException.class,()->application.start(2,id,request)).getCode());
        run();var c=new AgentRecipeApplication.Command("fields",store.recipes().latest(store.owned(id,1,false)).version(),"ANSWER",Map.of("school","辽宁学院"),null);
        application.command(1,id,runId,c);
        when(planner.decide(any())).thenAnswer(a->{AgentContext context=a.getArgument(0);assertEquals("本次目标是辽宁校园片",context.goal());assertEquals("校园策划",context.recipe().path("instruction").asText());return ask();});run();
    }
    // 【测什么】两个脚本阶段后的确认绑定最近前置作品B，批准B也不能拿历史A作为实际输入。
    // 【怎么算红】requiredArtifact选第一个同类型或去掉input.source绑定检查，B引用/错误输入断言失败。
    @Test void approvalUsesLatestPredecessorAndRejectsDifferentSource() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());boardStage(true);
        var s=store.owned(id,1,false);var d=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(store.recipes().definition(store.recipes().latest(s)));
        var stages=(com.fasterxml.jackson.databind.node.ArrayNode)d.path("stages");var second=stages.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("id","script2");stages.insert(1,second);
        db.update("UPDATE creative_recipe_version SET definition_json=? WHERE id='version'",d.toString());
        var a=store.artifact(s,"a",null,"SCRIPT","旧脚本","旧");var b=store.artifact(s,"b",null,"SCRIPT","新脚本","新");
        var results=json.createObjectNode();results.set("script",json.createObjectNode().put("artifactId",a.id()).put("version",1).put("type","SCRIPT"));results.set("script2",json.createObjectNode().put("artifactId",b.id()).put("version",1).put("type","SCRIPT"));
        db.update("UPDATE agent_recipe_run SET stage_index=2,variables_json='{\"school\":\"辽宁\"}',results_json=? WHERE id='run'",results.toString());
        run();assertEquals(b.id(),app.snapshot(1,id).state().recipeRun().path("approvalArtifact").path("artifactId").asText());
        commands().command(1,id,"run",command("approve","APPROVE_ARTIFACT",null,new AgentRecipeApplication.Reference(b.id(),1)));
        var descriptor=new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD");
        var bad=json.createObjectNode();bad.set("source",json.createObjectNode().put("artifactId",a.id()).put("version",1));
        assertTrue(assertThrows(org.example.seedancegenarate.agent.model.InvalidAgentDecisionException.class,()->store.recipes().validateCall(store,s,store.turn(accepted.turn().id()),descriptor,bad)).getMessage().contains("source"));
        var p=store.artifact(s,"p",null,"PLAN","计划","计划");db.update("UPDATE agent_artifact_version SET data_json='{\"goal\":\"分镜\",\"steps\":[{\"id\":\"board-step\",\"kind\":\"STORYBOARD\",\"title\":\"分镜\"}]}' WHERE artifact_id=?",p.id());
        var w=store.workspace(s);w.set("planRef",json.createObjectNode().put("artifactId",p.id()).put("version",1));store.saveWorkspace(s,w);
        new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models).apply(1,id,new AgentWorkspaceApplication.Command("adopt",0L,"ADOPT_PLAN",new AgentContext.ArtifactRef(p.id(),1,null)));
        var input=json.createObjectNode();input.set("source",json.createObjectNode().put("artifactId",b.id()).put("version",1));
        assertDoesNotThrow(()->store.recipes().validateCall(store,s,store.turn(app.snapshot(1,id).turn().id()),descriptor,input));
        db.update("UPDATE agent_recipe_run SET status='WAITING_CONFIRMATION' WHERE id='run'");
        store.artifact(s,"revision",b.id(),"SCRIPT","被编辑的新版本","修改内容");
        var stale=app.snapshot(1,id);assertFalse(stale.state().recipeRun().hasNonNull("approvalArtifact"));assertTrue(stale.state().recipeRun().path("reason").asText().contains("改变"));
        assertThrows(BusinessException.class,()->commands().command(1,id,"run",command("stale-approve","APPROVE_ARTIFACT",null,new AgentRecipeApplication.Reference(b.id(),1))));
        commands().command(1,id,"run",command("stop","STOP",null,null));assertEquals("CANCELLED",app.snapshot(1,id).state().recipeRun().path("status").asText());
    }
    // 【测什么】Run预算不会因采用新Turn重置，错误Plan不能落部分执行；取消隔离迟到结果。
    // 【怎么算红】删run预算、adopt阶段映射或取消epoch，Planner调用/plan数量/旧结果断言失败。
    @Test void planValidationBudgetAndLateEpochAreFenced() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());boardStage(false);run();answerSchool();
        assertThrows(BusinessException.class,()->adoptPlan(false));assertEquals(0,count("agent_plan"));
        db.update("UPDATE agent_recipe_run SET decision_count=63,interaction_count=15 WHERE id='run'");
        adoptPlan(true);assertEquals(63,store.recipes().latest(store.owned(id,1,false)).decisions());assertEquals(15,store.recipes().latest(store.owned(id,1,false)).interactions());
        assertNotEquals(accepted.turn().id(),app.snapshot(1,id).turn().id());
        db.update("UPDATE agent_recipe_run SET decision_count=64 WHERE id='run'");run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());verifyNoInteractions(planner);
        assertThrows(BusinessException.class,()->commands().command(1,id,"run",command("resume","RESUME",null,null)));
        var old=store.turn(app.snapshot(1,id).turn().id());var frozen=store.recipes().freeze(old);
        commands().command(1,id,"run",command("stop","STOP",null,null));assertFalse(store.recipes().matches(old,frozen));assertEquals("CANCELLED",app.snapshot(1,id).state().recipeRun().path("status").asText());
    }
    // 【测什么】16次人工交互是整个Run硬上限；停止允许处理已被其它Turn抢占的历史运行且不取消新Turn。
    // 【怎么算红】删human计数条件或STOP无条件取消session当前Turn，409/新Turn状态断言失败。
    @Test void interactionBudgetAndDisplacedRunStopAreSafe() {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());run();
        db.update("UPDATE agent_recipe_run SET interaction_count=16 WHERE id='run'");
        assertThrows(BusinessException.class,()->answerSchool());assertFalse(store.recipes().latest(store.owned(id,1,false)).variables().has("school"));
        var s=store.owned(id,1,false);String direct=store.newDirectTurn(s,"直接生成");
        commands().command(1,id,"run",command("stop","STOP",null,null));
        assertEquals("QUEUED",store.turn(direct).status());assertEquals("CANCELLED",store.recipes().latest(s).status());
    }
    // 【测什么】技能执行过程中停止Run，迟到成功不得产生作品或下一阶段；失效Run也不能通过聊天偷偷恢复。
    // 【怎么算红】删activeTurn/epoch或Run冻结校验，旧作品或新增作业会漏出；删send限制则不再409。
    @Test void stopDuringSkillDiscardsResultAndFailedRunNeedsExplicitResume() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());run();answerSchool();adoptPlan(false);
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","脚本",json.createObjectNode(),"SCRIPT"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));run();
        long artifacts=count("agent_artifact_version");
        when(skill.execute(any(),any())).thenAnswer(a->{commands().command(1,id,"run",command("stop","STOP",null,null));return new SkillResult("SCRIPT","迟到","迟到作品",null);});run();
        assertEquals(artifacts,count("agent_artifact_version"));assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        db.update("UPDATE agent_recipe_run SET status='FAILED' WHERE id='run'");
        assertEquals(409,assertThrows(BusinessException.class,()->app.send(1,id,send("bypass","继续"))).getCode());
    }
    // 【测什么】无内容确认门槛的脚本→分镜自动连续执行，不靠前端或额外“继续”消息。
    // 【怎么算红】删Skill结果后的继续入队或stage推进，五个作业后不能得到两个成功作品和COMPLETED。
    @Test void textStagesWithoutApprovalContinueAutonomously() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());boardStage(false);run();answerSchool();adoptPlan(true);
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","脚本",json.createObjectNode(),"SCRIPT"));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","校园故事",null));
        var board=mock(CreativeSkill.class);when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD"));
        skills=new SkillRegistry(List.of(skill,board));runtime=new org.example.seedancegenarate.agent.runtime.AgentRuntime(store,jobs,tx,planner,skills,json,mock(org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0);String stage=c.recipe().path("currentStage").path("id").asText();
            if("script".equals(stage))return new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode());
            if("board".equals(stage)){var input=json.createObjectNode();var source=c.recipe().path("results").path("script");input.set("source",json.createObjectNode().put("artifactId",source.path("artifactId").asText()).put("version",source.path("version").asInt()));return new AgentDecision("CALL_SKILL",null,null,List.of(),"storyboard-generation",input);}
            return new AgentDecision("COMPLETE","完成",null,List.of(),null,null);
        });
        when(board.execute(any(),any())).thenAnswer(a->{AgentContext c=a.getArgument(0);return new SkillResult("STORYBOARD","分镜","校园画面",null,json.readTree("{\"scenes\":[{\"sceneId\":\"s1\"}]}"),c.selection());});
        for(int i=0;i<4;i++)run(); // two Decision/Skill pairs, no final model call
        assertEquals("COMPLETED",app.snapshot(1,id).state().recipeRun().path("status").asText());assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals(2,store.recipes().latest(store.owned(id,1,false)).results().size());assertEquals(1,store.recipes().latest(store.owned(id,1,false)).interactions());
    }
    // 【测什么】阶段必填变量即使不是全局必填，当前表单仍显示必填；纯收集阶段回答后自动越过。
    // 【怎么算红】fields直接暴露原required=false或不推进无产物阶段，必填/当前stage断言失败。
    @Test void stageOnlyFieldsAreRequiredAndCollectionStageAdvances() {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());var s=store.owned(id,1,false);
        var d=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(store.recipes().definition(store.recipes().latest(s)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)d.path("variables").get(0)).put("required",false);
        var stage=json.createObjectNode().put("id","collect").put("title","收集需求").put("instruction","确认学校").put("requiresArtifactApproval",false);
        stage.set("allowedSkills",json.createArrayNode());stage.set("requiredVariables",json.valueToTree(List.of("school")));((com.fasterxml.jackson.databind.node.ArrayNode)d.path("stages")).insert(0,stage);
        db.update("UPDATE creative_recipe_version SET definition_json=? WHERE id='version'",d.toString());run();
        assertTrue(app.snapshot(1,id).state().recipeRun().path("fields").get(0).path("required").asBoolean());answerSchool();
        when(planner.decide(any())).thenAnswer(a->{AgentContext c=a.getArgument(0);assertEquals("script",c.recipe().path("currentStage").path("id").asText());assertFalse(c.recipe().toString().contains("确认学校"));return ask();});run();
        assertEquals(1,store.recipes().latest(s).stage());
    }
    // 【测什么】工作区引用变更取消旧Turn后，显式恢复重新绑定Plan但保留Run预算，不留下取消状态的活计划。
    // 【怎么算红】RESUME对CANCELLED Turn只continue而不newTurn/bind，plan状态仍CANCELLED或Turn身份相同。
    @Test void resumeAfterWorkspaceSelectionRebindsPlanAndPreservesRunBudget() throws Exception {
        var accepted=app.send(1,id,send("start","校园片"));seedRun(accepted.turn().id());run();answerSchool();adoptPlan(false);
        String old=app.snapshot(1,id).turn().id();var s=store.owned(id,1,false);long interactions=store.recipes().latest(s).interactions();
        new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models).apply(1,id,new AgentWorkspaceApplication.Command("select",store.workspace(s).path("version").asLong(),"SELECT",null));
        assertEquals("SUSPENDED",app.snapshot(1,id).state().recipeRun().path("status").asText());
        commands().command(1,id,"run",command("resume","RESUME",null,null));
        assertNotEquals(old,app.snapshot(1,id).turn().id());assertEquals("RUNNING",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
        assertEquals(interactions+1,store.recipes().latest(s).interactions());assertEquals("run",app.snapshot(1,id).state().recipeRun().path("id").asText());
    }
}
