package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.agent.skill.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reuses the real SQL/transaction fixture; never calls a paid provider. */
class AgentPersistentPlanTest extends AgentRuntimeIntegrationTest {
    // 【测什么】已采用计划脚本成功后，分镜格式两次失败只暂停，成功步骤和作品引用保持不变。
    // 【怎么算红】文字错误走terminalFailure或修复重做前序Skill时，Plan状态/作品引用/脚本调用数失败。
    @Test void storyboardOutputRepairExhaustionPreservesAdoptedPlan() {
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        startScript();when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));run();
        var before=app.snapshot(1,id).state().workspace().path("steps").get(0).deepCopy();
        var source=app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        var board=mock(CreativeSkill.class);
        when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD"));
        when(board.execute(any(),any())).thenThrow(new SkillOutputContractException("DOCUMENT_SCHEMA $: 字段 scenes 必须为1..12项数组"));
        var input=json.createObjectNode().put("instruction","分镜");input.set("source",json.valueToTree(new AgentContext.ArtifactRef(source.id(),source.version(),null)));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"storyboard-generation",input));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(skill,board)),json,mock(AgentGenerationRuntime.class),diagnostics,models);
        run();run();db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");run();
        var after=app.snapshot(1,id);
        assertEquals("SUSPENDED",after.turn().status());assertEquals("SUSPENDED",after.state().workspace().path("executionStatus").asText());
        assertEquals(before,after.state().workspace().path("steps").get(0));
        assertEquals(2,count("agent_artifact_version"));verify(skill,times(1)).execute(any(),any());verify(board,times(2)).execute(any(),any());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_plan WHERE status='FAILED'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】完整单反引号包装且省略可选字段的合法调用直接受理，不消耗修正轮次或重复派发Skill。
    // 【怎么算红】不剥包装或强制可选字段会step增加/ERROR观察；绕过作业fence会重复建SkillCall。
    @Test void wrappedDecisionDoesNotConsumeRepairBudgetOrDuplicateSkill() {
        adopt();var gateway=mock(AgentModelGateway.class);
        when(skill.descriptor()).thenReturn(new ScriptGenerationSkill(gateway,json).descriptor());
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn("`json\n{\"decision\":{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"生成脚本\"}}}\n`");
        planner=new AgentPlanner(gateway,skills,json);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        var job=next();runtime.execute(job,false);runtime.execute(job,false);
        assertEquals("WAITING_SKILL",app.snapshot(1,id).turn().status());
        assertEquals(0,store.turn(app.snapshot(1,id).turn().id()).step());assertEquals(0,count("agent_observation"));
        assertEquals(2,count("agent_skill_call")); // fixture plan call + one accepted script call
        verify(gateway,times(1)).completeDecision(any(),anyString(),anyString(),any());
        verifyNoInteractions(diagnostics);
    }
    // 【测什么】格式拒绝及状态拒绝均将精确原文送开发诊断，不混入Observation/用户快照。
    // 【怎么算红】只记录parser错误漏掉合法JSON的提前COMPLETE，或把raw写Observation会失败。
    @Test void diagnosticsCaptureParserAndRuntimeRejectionWithoutPublishingRaw() {
        adopt();var gateway=mock(AgentModelGateway.class);
        when(skill.descriptor()).thenReturn(new ScriptGenerationSkill(gateway,json).descriptor());
        planner=new AgentPlanner(gateway,skills,json);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        String malformed="{\"type\":\"WAIT\",\"privateMarker\":\"raw-secret\"}";
        String premature="  {\"decision\":{\"type\":\"COMPLETE\",\"text\":\"已完成\",\"summary\":null}}  ";
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn(malformed,premature);
        run();run();
        verify(diagnostics).record(anyString(),anyLong(),eq(0),anyString(),anyString(),eq(malformed));
        verify(diagnostics).record(anyString(),anyLong(),eq(1),anyString(),anyString(),eq(premature));
        assertFalse(store.write(app.snapshot(1,id)).contains("raw-secret"));
        assertFalse(db.queryForList("SELECT detail FROM agent_observation").toString().contains("raw-secret"));
        assertEquals(0,count("agent_decision")); // both rejected decisions rolled back
    }
    // 【测什么】已成功的脚本在下一规划调用重试耗尽后仍成功，Plan暂停而非FAILED。
    // 【怎么算红】恢复模型错误terminalFailure/清除Plan结果，Plan状态或首步产物引用断言失败。
    @Test void exhaustedPlannerRetryPreservesSuccessfulPlanArtifacts() {
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        startScript();when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));run();
        when(planner.decide(any())).thenThrow(org.example.seedancegenarate.service.llm.LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("timeout")));
        run();assertEquals("WAITING_RETRY",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
        db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW())");run();
        var workspace=app.snapshot(1,id).state().workspace();assertEquals("SUSPENDED",workspace.path("executionStatus").asText());
        assertEquals("SUCCEEDED",workspace.path("steps").get(0).path("status").asText());
        assertEquals(2,count("agent_artifact_version"));verify(skill,times(1)).execute(any(),any());
    }
    void adopt() {
        adopt(List.of(Map.of("id","script","kind","SCRIPT","title","脚本"),Map.of("id","board","kind","STORYBOARD","title","分镜")));
    }
    void adopt(List<Map<String,String>> steps) {
        var s=store.owned(id,1,false);
        var draft=store.call(store.newCall(store.turn(store.newTurn(s,"self-hosted","宣传片")),"plan-generation","1",json.createObjectNode()));
        var data=json.valueToTree(Map.of("goal","宣传片","constraints",List.of(),"steps",steps));
        var p=tx.execute(t->store.recordResult(s,draft,new SkillResult("PLAN","计划","先脚本后分镜",null,data,null)));
        store.status(draft.turnId(),"COMPLETED",null);
        when(models.defaultChannel()).thenReturn("self-hosted");
        var workspace=new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models);
        var command=new AgentWorkspaceApplication.Command("adopt",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(p.id(),1,null));
        workspace.apply(1,id,command); workspace.apply(1,id,command);
    }
    // 【测什么】采用响应丢失重放只启动一次，脚本后自动观察并继续分镜，无须再发继续。
    // 【怎么算红】恢复采用不入队或finishSkill的planConfirmed终轮条件，排队/第二作品断言失败。
    @Test void adoptingPlanRunsTextStepsWithoutAnotherUserMessage() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","text",json.createObjectNode(),"SCRIPT"));
        adopt(); assertEquals(1,count("job_probe"));
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());
        assertEquals(1,count("agent_plan")); assertEquals(2,count("agent_plan_step"));
        when(planner.decide(any())).thenReturn(callScript());
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","脚本正文",null));
        run(); var first=next(); run(); runtime.execute(first,true);
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("steps").get(0).path("status").asText());
        var script=app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        CreativeSkill board=mock(CreativeSkill.class);
        when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","分镜",json.createObjectNode(),"STORYBOARD"));
        var source=new AgentContext.ArtifactRef(script.id(),script.version(),null);
        var boardInput=json.createObjectNode().put("instruction","分镜"); boardInput.set("source",json.valueToTree(source));
        when(planner.decide(any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0); assertEquals("SUCCEEDED",c.observations().get(0).path("code").asText());
            assertEquals("board",c.plan().path("currentStepId").asText());
            return new AgentDecision("CALL_SKILL",null,null,List.of(),"storyboard-generation",boardInput);
        });
        var boardData=json.valueToTree(Map.of("scenes",List.of(Map.of("sceneId","s1","title","开场","visual","雷达","narration","欢迎"))));
        when(board.execute(any(),any())).thenReturn(new SkillResult("STORYBOARD","分镜","分镜正文",null,boardData,source));
        // Rebuild the runtime between successful script and automatic storyboard decision.
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(skill,board)),json,mock(AgentGenerationRuntime.class),diagnostics,models);
        run(); run();
        // Last required artifact deterministically completes; no closing Planner job is created.
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
        assertEquals(2,count("agent_observation")); assertEquals(3,count("agent_artifact_version"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'",Integer.class));
        verify(skill,times(1)).execute(any(),any()); verify(board,times(1)).execute(any(),any());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    AgentDecision callScript() { return new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","脚本")); }
    void startScript() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        adopt(); when(planner.decide(any())).thenReturn(callScript()); run();
    }
    // 【测什么】计划内真实问答回答自动恢复同一Turn，已确认选择与计划进度不丢失。
    // 【怎么算红】回答创建新Turn或不入队，Turn身份/QUEUED/执行次数断言失败。
    @Test void planQuestionResumesSameRun() {
        adopt(); String turn=app.snapshot(1,id).turn().id(); when(planner.decide(any())).thenReturn(ask()); run();
        assertEquals("WAITING_USER",app.snapshot(1,id).state().workspace().path("steps").get(0).path("status").asText());
        var interaction=store.pending(turn); var answer=new AgentApplication.Answer("choice","a",1);
        app.answer(1,id,interaction.id(),answer); app.answer(1,id,interaction.id(),answer);
        assertEquals(turn,app.snapshot(1,id).turn().id()); assertEquals(2,count("job_probe"));
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        when(planner.decide(any())).thenAnswer(a->{AgentContext c=a.getArgument(0); assertTrue(c.confirmedChoices().get(0).contains("科技风")); return callScript();}); run();
        assertEquals("WAITING_SKILL",app.snapshot(1,id).turn().status());
    }
    // 【测什么】真实Planner非法JSON会被观察并修正，新decision_seq而非同job原样重试。
    // 【怎么算红】删除observe/continueTurn或Parser不分类，第二次context没有错误且step不增长。
    @Test void malformedDecisionProducesBoundedFeedback() {
        var gateway=mock(AgentModelGateway.class);
        when(skill.descriptor()).thenReturn(new ScriptGenerationSkill(gateway,json).descriptor());
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn("```bad```","{\"decision\":{\"type\":\"ASK_USER\",\"text\":\"需要什么风格？\",\"summary\":null,\"options\":null}}");
        planner=new AgentPlanner(gateway,skills,json); runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        adopt(); var failed=next(); runtime.execute(failed,false);
        assertEquals(1,store.turn(app.snapshot(1,id).turn().id()).step()); assertEquals(1,count("agent_observation"));
        assertEquals("FAILED",db.queryForObject("SELECT status FROM agent_model_recovery WHERE step_no=0",String.class));
        assertTrue(db.queryForObject("SELECT done FROM job_probe WHERE id=?",Boolean.class,failed.getId()));
        run(); assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        var contexts=org.mockito.ArgumentCaptor.forClass(AgentContext.class);
        verify(gateway,times(2)).completeDecision(contexts.capture(),anyString(),anyString(),any());
        assertEquals("INVALID_DECISION",contexts.getAllValues().get(1).observations().get(0).path("code").asText());
        verify(jobs,never()).failAndRetry(any(),anyString());
    }
    // 【测什么】未完成计划不能以COMPLETE隐藏；连续无效决策两次修正后暂停，可明确恢复且旧job不重跑。
    // 【怎么算红】删除complete守卫/修正预算/暂停路径，会错误COMPLETED、FAILED或一直QUEUED。
    @Test void prematureCompletionIsRejectedAndRepairIsBounded() {
        adopt(); when(planner.decide(any())).thenReturn(new AgentDecision("COMPLETE","已完成",null,List.of(),null,null));
        var old=next();run(); run(); run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status()); assertEquals(3,count("agent_observation"));
        assertEquals("SUSPENDED",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
        assertNotEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
        assertEquals(0,count("agent_skill_call")-1); // only the draft fixture call
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        String turn=app.snapshot(1,id).turn().id();
        app.send(1,id,new AgentApplication.Send("repair-resume","继续原计划",null));
        assertEquals(turn,app.snapshot(1,id).turn().id());
        runtime.execute(old,false);verify(planner,times(3)).decide(any());
        run();assertEquals("QUEUED",app.snapshot(1,id).turn().status()); // new bounded repair window
    }
    // 【测什么】活动计划普通回复成为真实自由交互，不冒称完成、不创建Skill，回答后沿同轮继续。
    // 【怎么算红】把RESPOND等同COMPLETE或不建interaction，WAITING_USER/恢复断言失败。
    @Test void responseOnActivePlanWaitsForUserWithoutCompletingOrExecuting() {
        adopt();when(planner.decide(any())).thenReturn(new AgentDecision("RESPOND","需要你确定宣传片风格",null,List.of(),null,null));
        var job=next();run();runtime.execute(job,false);
        var snapshot=app.snapshot(1,id);
        assertEquals("WAITING_USER",snapshot.turn().status());
        assertEquals("WAITING_USER",snapshot.state().workspace().path("executionStatus").asText());
        assertEquals(1,count("agent_interaction"));assertEquals(0,count("agent_observation"));
        assertEquals(1,count("agent_skill_call"));verify(skill,never()).execute(any(),any());
        assertNotNull(store.pending(snapshot.turn().id()));
        assertTrue(store.write(snapshot.messages()).contains("计划尚有未完成步骤"));
        var resumed=app.send(1,id,new AgentApplication.Send("style","科技纪录片",null));
        assertEquals(snapshot.turn().id(),resumed.turn().id());assertEquals("QUEUED",resumed.turn().status());
    }
    // 【测什么】中间成功调用并完成脚本后，旧格式错误不消耗下一次连续修正预算。
    // 【怎么算红】按整个Turn累计ERROR，脚本成功后的首次错误会直接暂停。
    @Test void successfulSkillBreaksConsecutiveDecisionFailures() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","text",json.createObjectNode(),"SCRIPT"));
        adopt();when(planner.decide(any())).thenThrow(new InvalidAgentDecisionException("$.type: 不支持的动作"));run();run();
        doReturn(callScript()).when(planner).decide(any());run();
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));run();
        when(planner.decide(any())).thenThrow(new InvalidAgentDecisionException("$.options: 必须为数组"));run();
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());run();assertEquals("QUEUED",app.snapshot(1,id).turn().status());run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("steps").get(0).path("status").asText());
        verify(skill,times(1)).execute(any(),any());
    }
    // 【测什么】合法4000字符回复加事实提示后，interaction.question仍不超过数据库上限。
    // 【怎么算红】将固定提示拼到question字段，INSERT抛DataAccessException而非WAITING_USER。
    @Test void maxLengthResponseKeepsNoticeOutsideQuestionColumn() {
        adopt();String text="答".repeat(4000);
        when(planner.decide(any())).thenReturn(new AgentDecision("RESPOND",text,null,List.of(),null,null));
        run();var snapshot=app.snapshot(1,id);
        assertEquals("WAITING_USER",snapshot.turn().status());
        assertEquals(text,store.pending(snapshot.turn().id()).question());
        assertTrue(store.write(snapshot.messages()).contains("计划尚有未完成步骤"));
    }
    // 【测什么】完成真实唯一步骤后，即使旧workspace保存了过时currentStepId，仍按持久步骤完成。
    // 【怎么算红】不project直接信任workspace指针，COMPLETE被拒绝导致状态断言失败。
    @Test void savedStalePointerDoesNotOverrideCompletedStepTruth() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","text",json.createObjectNode(),"SCRIPT"));
        adopt(List.of(Map.of("id","script","kind","SCRIPT","title","脚本")));
        when(planner.decide(any())).thenReturn(callScript());run();
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));
        var session=store.owned(id,1,false);var stale=store.workspace(session);stale.put("currentStepId","script");store.saveWorkspace(session,stale);
        run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
    }
    // 【测什么】确定性Skill业务失败落观察并停止，不会让小maxAttempts导致QUEUED+DEAD。
    // 【怎么算红】恢复统一failAndRetry，never校验和FAILED断言失败。
    @Test void permanentSkillFailureIsNotBlindlyRetried() {
        startScript(); when(skill.execute(any(),any())).thenThrow(BusinessException.badRequest("secret-provider-response"));
        var job=next(); job.setMaxAttempts(1); runtime.execute(job,true);
        assertEquals("FAILED",app.snapshot(1,id).turn().status()); verify(jobs,never()).failAndRetry(any(),anyString());
        assertEquals("FAILED",db.queryForObject("SELECT status FROM agent_model_recovery WHERE phase='TEXT_SKILL'",String.class));
        assertEquals("BUSINESS_400",db.queryForObject("SELECT code FROM agent_observation",String.class));
        assertFalse(db.queryForObject("SELECT detail FROM agent_observation",String.class).contains("secret-provider"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】旧workspace JSON不能伪造成功；成功脚本不能被自动source引用变成重新执行授权。
    // 【怎么算红】移除project或恢复source匹配成功步骤捷径，状态/Skill调用数断言失败。
    @Test void projectionCannotOverrideTruthAndSuccessfulStepsCannotRepeat() {
        startScript(); var s=store.owned(id,1,false); var w=store.workspace(s);
        ((com.fasterxml.jackson.databind.node.ObjectNode)w.path("steps").get(0)).put("status","SUCCEEDED"); store.saveWorkspace(s,w);
        assertEquals("RUNNING",store.workspace(s).path("steps").get(0).path("status").asText());
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null)); run();
        var a=app.snapshot(1,id).artifacts().stream().filter(x->"SCRIPT".equals(x.type())).findFirst().orElseThrow();
        var input=json.createObjectNode().put("instruction","再写"); input.set("source",json.valueToTree(new AgentContext.ArtifactRef(a.id(),1,null)));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input)); run();
        assertEquals("SUCCEEDED",store.workspace(s).path("steps").get(0).path("status").asText());
        assertEquals(2,count("agent_skill_call")); verify(skill,times(1)).execute(any(),any());
    }
    // 【测什么】取消发生在外部Skill执行期间，迟到作品/观察不得落库或推进Step。
    // 【怎么算红】去掉finishSkill当前epoch校验，artifact/observation会增加。
    @Test void cancellationFencesLatePlanResult() {
        startScript(); when(skill.execute(any(),any())).thenAnswer(a->{app.cancel(1,id,app.snapshot(1,id).turn().id());return new SkillResult("SCRIPT","迟到","正文",null);}); run();
        assertEquals("CANCELLED",app.snapshot(1,id).turn().status()); assertEquals(1,count("agent_artifact_version")); assertEquals(0,count("agent_observation"));
        assertEquals("CANCELLED",app.snapshot(1,id).state().workspace().path("executionStatus").asText());
    }
    // 【测什么】第16次决策后旧Turn原子Yield到唯一SYSTEM_CONTINUE Turn，原Plan/Recipe/人工预算/epoch继承，重放不双建。
    // 【怎么算红】恢复旧SUSPENDED、重置resume_count/epoch、用workspace新建Plan，或去掉parent/job幂等，状态、身份或计数断言必须变红。
    @Test void decisionBudgetYieldsAndReplaysCannotDuplicateContinuation() {
        adopt();
        var session=store.owned(id,1,false); var old=store.turn(app.snapshot(1,id).turn().id());
        String plan=store.workspace(session).path("executionPlanId").asText();
        db.update("UPDATE agent_turn SET step_no=16,resume_count=3,budget_start=11 WHERE id=?",old.id());
        db.update("UPDATE job_probe SET payload=?",store.write(new AgentRuntime.Payload(old.id(),old.epoch(),16,null)));
        String definition="""
                {"instruction":"yield","requiredInputs":[],"variables":[],
                "stages":[{"id":"script","title":"script","instruction":"write","allowedSkills":["script-generation"],"requiredVariables":[],"requiresArtifactType":null,"requiresArtifactApproval":false,"outputType":"SCRIPT"}],
                "requiredCapabilities":["script-generation"],"acceptanceRules":[]}
                """;
        db.update("INSERT INTO creative_recipe(id,owner_id,name,description,instruction) VALUES('yield-recipe',1,'yield','yield','yield')");
        db.update("INSERT INTO creative_recipe_version(id,recipe_id,version,name,description,instruction,definition_json,content_hash,compiler_version) VALUES('yield-version','yield-recipe',1,'yield','yield','yield',?,'hash','1')",definition);
        db.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,turn_id,goal,execution_epoch,variables_json,results_json,approvals_json,decision_count,interaction_count) VALUES('yield-run',?,? ,?,'goal',7,'{}','{}','{}',5,2)",session.id(),"yield-version",old.id());
        db.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,turn_id,goal,execution_epoch,variables_json,results_json,approvals_json) VALUES('shadow-run',?,?,?,'old',9,'{}','{}','{}')",session.id(),"yield-version",old.id());
        db.update("UPDATE agent_session SET active_recipe_run_id='yield-run' WHERE id=?",session.id());
        long messages=count("conversation_message"); long turns=count("agent_turn");
        var oldLease=next(); runtime.execute(oldLease,false);

        var snapshot=app.snapshot(1,id); var child=store.turn(snapshot.turn().id());
        assertNotEquals(old.id(),child.id()); assertEquals("YIELDED",store.turn(old.id()).status());
        assertEquals("DECISION_BUDGET",db.queryForObject("SELECT yield_reason FROM agent_turn WHERE id=?",String.class,old.id()));
        var identity=db.queryForMap("SELECT trigger_type,parent_turn_id,turn_seq,resume_count,epoch FROM agent_turn WHERE id=?",child.id());
        assertEquals("SYSTEM_CONTINUE",identity.get("TRIGGER_TYPE")); assertEquals(old.id(),identity.get("PARENT_TURN_ID"));
        assertNotNull(identity.get("TURN_SEQ")); assertEquals(3,((Number)identity.get("RESUME_COUNT")).intValue());
        assertEquals(old.epoch(),((Number)identity.get("EPOCH")).longValue()); assertEquals(old.channel(),child.channel());
        assertEquals(plan,store.workspace(store.owned(id,1,false)).path("executionPlanId").asText());
        assertEquals(child.id(),db.queryForObject("SELECT turn_id FROM agent_plan WHERE id=?",String.class,plan));
        assertEquals(child.id(),db.queryForObject("SELECT turn_id FROM agent_recipe_run WHERE id='yield-run'",String.class));
        assertEquals(old.id(),db.queryForObject("SELECT turn_id FROM agent_recipe_run WHERE id='shadow-run'",String.class));
        assertEquals(7,db.queryForObject("SELECT execution_epoch FROM agent_recipe_run WHERE id='yield-run'",Integer.class));
        assertEquals(5,db.queryForObject("SELECT decision_count FROM agent_recipe_run WHERE id='yield-run'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT interaction_count FROM agent_recipe_run WHERE id='yield-run'",Integer.class));
        assertEquals(messages,count("conversation_message")); assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));

        runtime.execute(oldLease,false);
        assertEquals(turns+1,db.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE session_id=?",Long.class,session.id()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE parent_turn_id=?",Integer.class,old.id()));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));

        db.update("UPDATE agent_turn SET status='SUSPENDED',step_no=3,resume_count=2,budget_start=0 WHERE id=?",child.id());
        app.send(1,id,new AgentApplication.Send("resume","continue",null));
        assertEquals(3,db.queryForObject("SELECT resume_count FROM agent_turn WHERE id=?",Integer.class,child.id()));
        assertEquals(4,db.queryForObject("SELECT budget_start FROM agent_turn WHERE id=?",Integer.class,child.id()));
        db.update("UPDATE agent_turn SET status='SUSPENDED',resume_count=8 WHERE id=?",child.id());
        assertThrows(BusinessException.class,()->app.send(1,id,new AgentApplication.Send("limit","continue",null)));
    }

    // 【测什么】SYSTEM_CONTINUE 作业入队失败时，旧Turn让出、child、Plan改绑与active指针必须整体回滚。
    // 【怎么算红】把yield或enqueue移出同一事务，异常后会留下YIELDED旧Turn、孤儿child或错误Plan绑定，本断言必须变红。
    @Test void continuationEnqueueFailureRollsBackTheWholeYield() {
        adopt(); var session=store.owned(id,1,false); var old=store.turn(app.snapshot(1,id).turn().id());
        String plan=store.workspace(session).path("executionPlanId").asText(); long turns=count("agent_turn");
        db.update("UPDATE agent_turn SET step_no=16 WHERE id=?",old.id());
        db.update("UPDATE job_probe SET payload=?",store.write(new AgentRuntime.Payload(old.id(),old.epoch(),16,null)));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("enqueue unavailable"))
                .when(jobs).enqueue(eq(AgentRuntime.STEP_JOB),anyString(),anyString());

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->runtime.execute(next(),false));
        assertEquals(old.id(),store.owned(id,1,false).activeTurnId());
        assertEquals("QUEUED",store.turn(old.id()).status());
        assertEquals(turns,count("agent_turn"));
        assertEquals(old.id(),db.queryForObject("SELECT turn_id FROM agent_plan WHERE id=?",String.class,plan));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_turn WHERE parent_turn_id=?",Integer.class,old.id()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
    }

    // 【测什么】Plan硬上限独立为100，进度只从durable Step重算，不会保存回workspace JSON。
    // 【怎么算红】保留8步限制、从workspace信任进度、使用0-based序号或把planProgress持久化，任一断言必须变红。
    @Test void oneHundredStepPlanHasReadOnlyDurableProgress() {
        var steps=new ArrayList<Map<String,String>>();
        for(int i=1;i<=100;i++)steps.add(Map.of("id","step-"+i,"kind","SCRIPT","title","步骤"+i));
        adopt(steps); var session=store.owned(id,1,false); var workspace=store.workspace(session);
        assertEquals(100,workspace.path("planProgress").path("totalSteps").asInt());
        assertEquals(0,workspace.path("planProgress").path("completedSteps").asInt());
        assertEquals(1,workspace.path("planProgress").path("currentStepNo").asInt());
        store.saveWorkspace(session,workspace);
        assertFalse(db.queryForObject("SELECT workspace_json FROM agent_session WHERE id=?",String.class,session.id()).contains("planProgress"));
        String plan=workspace.path("executionPlanId").asText();
        db.update("UPDATE agent_plan_step SET status='SUCCEEDED' WHERE plan_id=? AND ordinal_no=0",plan);
        assertEquals(2,store.workspace(session).path("planProgress").path("currentStepNo").asInt());
        assertEquals(1,store.workspace(session).path("planProgress").path("completedSteps").asInt());
        db.update("UPDATE agent_plan_step SET status='SUCCEEDED' WHERE plan_id=?",plan);
        assertTrue(store.workspace(session).path("planProgress").path("currentStepNo").isNull());
        assertEquals(100,store.workspace(session).path("planProgress").path("completedSteps").asInt());
    }

    // 【测什么】101步Plan在持久化任何执行事实前返回业务400。
    // 【怎么算红】去掉maxPlanSteps或用Recipe的8阶段上限混用，101步会被采用或返回错误状态。
    @Test void oneHundredAndOneStepPlanIsRejectedAsBadRequest() {
        var steps=new ArrayList<Map<String,String>>();
        for(int i=1;i<=101;i++)steps.add(Map.of("id","step-"+i,"kind","SCRIPT","title","步骤"+i));
        var error=assertThrows(BusinessException.class,()->adopt(steps));
        assertEquals(400,error.getCode()); assertEquals(0,count("agent_plan"));
    }
    // 【测什么】两个同类型SCRIPT步骤中，第二步不能借source或artifactId改写第一个成功作品。
    // 【怎么算红】删除同类型source/artifactId守卫，仅kind匹配时会新增SkillCall。
    @Test void sameKindNextStepCannotOverwriteSucceededArtifact() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        adopt(List.of(Map.of("id","first","kind","SCRIPT","title","初稿"),Map.of("id","second","kind","SCRIPT","title","另一稿")));
        when(planner.decide(any())).thenReturn(callScript()); when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","初稿","正文",null)); run(); run();
        var first=app.snapshot(1,id).artifacts().stream().filter(a->"SCRIPT".equals(a.type())).findFirst().orElseThrow();
        var input=json.createObjectNode().put("instruction","修改"); input.set("source",json.valueToTree(new AgentContext.ArtifactRef(first.id(),1,null)));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input)); run();
        input.remove("source"); input.put("artifactId",first.id());
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input)); run();
        assertEquals(2,count("agent_skill_call")); assertEquals(2,count("agent_artifact_version"));
        assertEquals("SUCCEEDED",app.snapshot(1,id).state().workspace().path("steps").get(0).path("status").asText());
    }
    // 【测什么】MySQL默认大小写不敏感唯一键下，a/A不能等到写表才报500。
    // 【怎么算红】只用原样HashSet判断重复，adopt不会返回400。
    @Test void caseFoldedStepDuplicatesAreRejectedBeforePersistence() {
        var error=assertThrows(BusinessException.class,()->adopt(List.of(Map.of("id","a","kind","SCRIPT","title","一"),Map.of("id","A","kind","SCRIPT","title","二"))));
        assertEquals(400,error.getCode()); assertEquals(0,count("agent_plan")); assertEquals(0,count("job_probe"));
    }
    // 【测什么】存量伪造/失踪产物不能被导入为SUCCEEDED，安全退回未完成并使后继失效。
    // 【怎么算红】移除validResult校验，缺失artifactRef的脚本会保持SUCCEEDED。
    @Test void legacyCompletedStepRequiresRealOwnedArtifact() {
        var s=store.owned(id,1,false); var call=store.call(store.newCall(store.turn(store.newTurn(s,"self","目标")),"plan-generation","1",json.createObjectNode()));
        var data=json.valueToTree(Map.of("goal","目标","steps",List.of(Map.of("id","s","kind","SCRIPT","title","脚本"))));
        var p=tx.execute(t->store.recordResult(s,call,new SkillResult("PLAN","计划","正文",null,data,null)));
        var w=store.workspace(s); w.put("planConfirmed",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)w.path("steps").get(0)).put("status","COMPLETED").set("artifactRef",json.createObjectNode().put("artifactId","missing").put("version",1));
        store.saveWorkspace(s,w); assertFalse(store.workspace(s).has("executionPlanId"));
        assertEquals(0,count("agent_plan"));
        tx.executeWithoutResult(t->store.plans().adopt(s,p,w));
        assertEquals("READY",store.workspace(s).path("steps").get(0).path("status").asText());
        assertFalse(store.workspace(s).path("steps").get(0).has("artifactRef"));
    }
    // 【测什么】计划首步即媒体时采用会唤醒Planner，但不会越过Planner/TaskSkill直接建确认单或任务。
    // 【怎么算红】恢复媒体无条件暂停，Turn不是QUEUED或没有唯一STEP作业时这条必须变红。
    @Test void mediaFirstAdoptionQueuesPlannerWithoutApprovalOrTask() {
        adopt(List.of(Map.of("id","image","kind","IMAGE","title","图片")));
        assertEquals("QUEUED",app.snapshot(1,id).turn().status()); assertEquals(1,count("job_probe"));
        assertEquals(0,count("agent_approval")); verifyNoInteractions(planner);
    }
    // 【测什么】导入真实已完成文本前缀后遇到媒体，从第一个未完成步继续规划且不直接建付费事实。
    // 【怎么算红】保留导入前缀遇媒体暂停，Turn/STEP作业和当前步断言必须变红。
    @Test void importedCompletedPrefixQueuesPlannerAtMediaStep() {
        var s=store.owned(id,1,false); var call=store.call(store.newCall(store.turn(store.newTurn(s,"self","目标")),"plan-generation","1",json.createObjectNode()));
        var data=json.valueToTree(Map.of("goal","目标","steps",List.of(Map.of("id","s","kind","SCRIPT","title","脚本"),Map.of("id","m","kind","IMAGE","title","图片"))));
        var p=tx.execute(t->store.recordResult(s,call,new SkillResult("PLAN","计划","正文",null,data,null)));
        var script=store.artifact(s,"old-script",null,"SCRIPT","旧脚本","正文");
        var w=store.workspace(s); w.put("planConfirmed",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)w.path("steps").get(0)).put("status","COMPLETED").set("artifactRef",json.createObjectNode().put("artifactId",script.id()).put("version",1));
        store.saveWorkspace(s,w);
        var workspace=new AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models);
        workspace.apply(1,id,new AgentWorkspaceApplication.Command("enable",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(p.id(),1,null)));
        var view=app.snapshot(1,id); assertEquals("QUEUED",view.turn().status()); assertEquals("m",view.state().workspace().path("currentStepId").asText());
        assertEquals("SUCCEEDED",view.state().workspace().path("steps").get(0).path("status").asText()); assertEquals(1,count("job_probe"));
        assertEquals(0,count("agent_approval")); verifyNoInteractions(planner);
    }
    // 【测什么】durable文本步成功后下一步是媒体，依然同Turn自动进入下一次Planner决策。
    // 【怎么算红】finishSkill恢复文本转媒体暂停时，QUEUED、当前媒体步或新STEP作业断言必须变红。
    @Test void textSuccessContinuesPlannerIntoMediaStep() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        adopt(List.of(Map.of("id","script","kind","SCRIPT","title","脚本"),Map.of("id","image","kind","IMAGE","title","图片")));
        when(planner.decide(any())).thenReturn(callScript()); when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));
        run(); run();
        var snapshot=app.snapshot(1,id);
        assertEquals("QUEUED",snapshot.turn().status()); assertEquals("image",snapshot.state().workspace().path("currentStepId").asText());
        assertEquals("SUCCEEDED",snapshot.state().workspace().path("steps").get(0).path("status").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
        assertEquals(0,count("agent_approval")); verify(skill,times(1)).execute(any(),any());
    }
}
