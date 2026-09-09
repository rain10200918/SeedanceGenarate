package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.application.AgentApprovalApplication;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSceneIntegrationTest extends AgentApprovalIntegrationTest {
    AgentWorkspaceApplication workspaceApp() {
        var model=mock(AgentModelGateway.class); when(model.defaultChannel()).thenReturn("local");
        return new AgentWorkspaceApplication(store,approvals,tx,json,jobs,model);
    }
    org.example.seedancegenarate.agent.api.AgentViews.Artifact board(int size) {
        var s=store.owned(conversation,1,false);
        var a=store.artifact(s,"board-fixture:"+UUID.randomUUID(),null,"STORYBOARD","四幕故事","四幕");
        var data=json.createObjectNode(); var scenes=data.putArray("scenes");
        for(int i=1;i<=size;i++) scenes.addObject().put("sceneId","s"+i).put("title","幕"+i).put("visual","画面"+i);
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),a.id());
        return store.artifactVersion(s,a.id(),1);
    }
    com.fasterxml.jackson.databind.JsonNode workspace() { return store.workspace(store.owned(conversation,1,false)); }
    // 【测什么】四幕显式启动后逐幕自动选source，一次批准只提交一个Task；第四幕前父步骤不能成功。
    // 【怎么算红】按第一幅作品把父step置SUCCEEDED，或去掉currentScene来源注入，幕进度/来源断言即红。
    @Test void allFourScenesContinueWithSeparateApprovals() throws Exception {
        var board=board(4); var command=new AgentWorkspaceApplication.Command("all",0L,"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(board.id(),1,null));
        workspaceApp().apply(1,conversation,command); workspaceApp().apply(1,conversation,command);
        when(skill.descriptor()).thenReturn(new SkillDescriptor("image-generation","1","image",json.createObjectNode(),"IMAGE"));
        when(planner.decide(any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0);
            if(c.plan().path("currentStepId").isNull()) return new AgentDecision("COMPLETE","完成",null,List.of(),null,null);
            assertNotNull(c.selection());
            var input=quote.inputSnapshot().deepCopy();
            return new AgentDecision("CALL_SKILL",null,null,List.of(),"image-generation",input);
        });
        for(int i=1;i<=4;i++) {
            run(); run();
            approval=db.queryForObject("SELECT id FROM agent_approval WHERE status='PENDING'",String.class);
            assertEquals("s"+i,store.callContext(approvals.get(approval).callId()).path("sourceRef").path("sceneId").asText());
            verify(gateway,times(i-1)).submit(anyLong(),any(),anyString());
            approvalApp.answer(1,conversation,approval,new AgentApprovalApplication.Answer("approve"+i,1,"APPROVE"));
            String task="task-"+i; when(gateway.submit(anyLong(),any(),anyString())).thenReturn(task); run();
            when(gateway.read(1,task)).thenReturn(new AgentGenerationGateway.TaskView(task,"SUCCESS","IMAGE",null,false,false,null));
            wake(); run();
            assertEquals(i,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
            assertEquals(i==4,workspace().path("currentStepId").isNull());
        }
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM agent_approval",Integer.class));
        verify(gateway,times(4)).submit(anyLong(),any(),anyString());
        // Explicitly requesting the same exact storyboard again reuses all four completed tasks.
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("all-again",workspace().path("version").asLong(),"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(board.id(),1,null)));
        assertTrue(workspace().path("currentStepId").isNull());run();
        verify(gateway,times(4)).submit(anyLong(),any(),anyString());
    }

    void start(org.example.seedancegenarate.agent.api.AgentViews.Artifact board) {
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("start",workspace().path("version").asLong(),"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(board.id(),1,null)));
        when(skill.descriptor()).thenReturn(new SkillDescriptor("image-generation","1","image",json.createObjectNode(),"IMAGE"));
    }
    // 【测什么】空/超限/重复幕及单幕引用均拒绝，不创建半份Plan。
    // 【怎么算红】删validateBoard或整板引用检查，assertThrows或Plan计数变红。
    @Test void malformedBoardsRejectWithoutPartialPlan() {
        for(int size:new int[]{0,13})assertThrows(BusinessException.class,()->start(board(size)));
        var b=board(2);var data=b.data().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)data.path("scenes").get(1)).put("sceneId","s1");
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),b.id());
        assertThrows(BusinessException.class,()->start(b));
        assertThrows(BusinessException.class,()->workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("single",0L,"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(b.id(),1,"s1"))));
        assertEquals(0,count("agent_plan"));
    }
    // 【测什么】取消Turn不撤销已提交Task，旧Task存在时不能启动同板重复付费。
    // 【怎么算红】删initialize中inflight查询，取消后第二次start就不再409。
    @Test void cancelledTurnWithInflightTaskCannotDuplicateStoryboard() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);
        var cmd=new AgentWorkspaceApplication.Command("again",1L,"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(b.id(),1,null));
        assertThrows(BusinessException.class,()->workspaceApp().apply(1,conversation,cmd));
        approve();when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();
        app.cancel(1,conversation,app.snapshot(1,conversation).turn().id());
        assertThrows(BusinessException.class,()->workspaceApp().apply(1,conversation,cmd));
        assertEquals(1,count("agent_plan"));verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】旧epoch迟到成功只存作品不推进旧幕；再次明确启动可复用该真实结果，直接轮到第二幕。
    // 【怎么算红】删recordMedia current fence或复用成功查询，将错误完成旧幕或再次请求第一幕费用。
    @Test void lateSuccessDoesNotAdvanceOldEpochAndCanBeExplicitlyReused() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();
        app.cancel(1,conversation,app.snapshot(1,conversation).turn().id());
        when(gateway.read(1,"tsk-1")).thenReturn(success(false));wake();var terminal=next();run();generation.execute(terminal);
        assertEquals(0,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("restart",1L,"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(b.id(),1,null)));
        assertEquals(1,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
        assertEquals(2,workspace().path("steps").get(0).path("sceneProgress").path("currentSceneNo").asInt());
        run();run();var pending=db.queryForObject("SELECT id FROM agent_approval WHERE status='PENDING'",String.class);
        assertEquals("s2",store.callContext(approvals.get(pending).callId()).path("sourceRef").path("sceneId").asText());
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】失败幕保留FAILED并暂停，重启/快照读取无续投、无隐式状态重写。
    // 【怎么算红】删sceneFailed或让project修改状态，失败幕状态或调用数断言变红。
    @Test void failedSceneSuspendsAndReadOnlyProjectionPreservesFacts() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);
        approve();when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();
        when(gateway.read(1,"tsk-1")).thenReturn(new AgentGenerationGateway.TaskView("tsk-1","FAILED","IMAGE",null,false,false,null));wake();run();
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        var facts=db.queryForList("SELECT * FROM agent_plan_scene ORDER BY ordinal_no");
        var reloaded=new AgentStore(db,json);for(int i=0;i<3;i++)reloaded.workspace(reloaded.owned(conversation,1,false));
        assertEquals(facts,db.queryForList("SELECT * FROM agent_plan_scene ORDER BY ordinal_no"));
        assertEquals("FAILED",workspace().path("steps").get(0).path("scenes").get(0).path("status").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】已批准但领域拒绝受理时，逐幕计划暂停且失败幕不自动重试。
    // 【怎么算红】reject直接FAILED结束轮或不写sceneFailed，Turn/幕状态断言变红。
    @Test void rejectedSubmissionPreservesSuspendedScenePlan() throws Exception {
        start(board(4));run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenThrow(new org.example.seedancegenarate.agent.generation.GenerationRejectedException("本次未受理"));
        run();assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        assertEquals("FAILED",workspace().path("steps").get(0).path("scenes").get(0).path("status").asText());
        assertEquals(0,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】采用整片Plan后，分镜完成即固定全部幕；旧普通媒体步依旧SINGLE。
    // 【怎么算红】删succeeded分镜初始化或scope校验，会出现0幕或错误前向引用获准。
    @Test void plannedStoryboardInitializesScenesButLegacyStepStaysSingle() {
        var s=store.owned(conversation,1,false);String turn=store.newTurn(s,"local","故事");
        var call=store.call(store.newCall(store.turn(turn),"plan-generation","1",json.createObjectNode()));
        var data=json.createObjectNode().put("goal","故事");data.putArray("constraints");var steps=data.putArray("steps");
        steps.addObject().put("id","board").put("kind","STORYBOARD").put("title","分镜");
        steps.addObject().put("id","images").put("kind","IMAGE").put("title","逐幕图像").put("scope","STORYBOARD_SCENES").put("sourceStepId","board");
        steps.addObject().put("id","single").put("kind","VIDEO").put("title","单个视频");
        var p=store.recordResult(s,call,new SkillResult("PLAN","计划","计划",null,data,null));
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("adopt",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(p.id(),1,null)));
        s=store.owned(conversation,1,false);var t=store.turn(s.activeTurnId());
        var b=board(4);var boardCall=store.call(store.newCall(t,"storyboard-generation","1",json.createObjectNode()));
        store.recordResult(s,boardCall,new SkillResult("STORYBOARD","分镜","分镜",null,b.data(),null));
        assertEquals(4,workspace().path("steps").get(1).path("sceneProgress").path("totalScenes").asInt());
        assertFalse(workspace().path("steps").get(2).has("sceneProgress"));
        var invalid=steps.get(1).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("sourceStepId","later");
        assertThrows(BusinessException.class,()->StructuredSkillSupport.validatePlanStepScope(invalid,Set.of("board")));
    }
    // 【测什么】已有全部图片可复用时，同一次恢复跨过图片父步并锁定后续视频第一幕。
    // 【怎么算红】currentScene只初始化旧currentStep然后return null，会取不到视频s1或父指针停在images。
    @Test void allReusedImagesSkipToFollowingVideoSceneInSameResume() throws Exception {
        allFourScenesContinueWithSeparateApprovals();
        var s=store.owned(conversation,1,false);
        var board=store.artifacts(s).stream().filter(a->"STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        var p=store.artifact(s,"next-plan",null,"PLAN","图片与视频","全部幕");
        var data=json.createObjectNode().put("goal","图片与视频");data.putArray("constraints");var steps=data.putArray("steps");
        steps.addObject().put("id","board").put("kind","STORYBOARD").put("title","分镜");
        for(String kind:List.of("IMAGE","VIDEO"))steps.addObject().put("id",kind).put("kind",kind).put("title",kind).put("scope","STORYBOARD_SCENES").put("sourceStepId","board");
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),p.id());
        var w=store.workspace(s);w.set("planRef",json.createObjectNode().put("artifactId",p.id()).put("version",1));w.put("planConfirmed",true);
        w.putArray("steps").addObject().put("id","board").put("kind","STORYBOARD").put("status","SUCCEEDED").set("artifactRef",json.createObjectNode().put("artifactId",board.id()).put("version",1));
        store.plans().adopt(s,store.artifactVersion(s,p.id(),1),w);store.saveWorkspace(s,w);
        var t=store.turn(store.newTurn(s,"local","全部幕"));
        var scene=store.plans().currentScene(store.owned(conversation,1,false),t);
        assertNotNull(scene);assertEquals("s1",scene.path("sourceRef").path("sceneId").asText());
        assertEquals("VIDEO",workspace().path("currentStepId").asText());
        assertEquals(4,workspace().path("steps").get(1).path("sceneProgress").path("completedScenes").asInt());
        verify(gateway,times(4)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】分镜新版即使沿用sceneId也不能复用旧版媒体作品。
    // 【怎么算红】成功作品复用去掉version比较，新版进度将错误显示4/4。
    @Test void revisedStoryboardNeverReusesPreviousVersionResults() throws Exception {
        allFourScenesContinueWithSeparateApprovals();var s=store.owned(conversation,1,false);
        var b=store.artifacts(s).stream().filter(a->"STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        var revised=store.artifact(s,"board-revision",b.id(),"STORYBOARD","新版","新版");
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=? AND version_no=2",b.data().toString(),b.id());
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("new-version",workspace().path("version").asLong(),"GENERATE_SCENES_IMAGE",new AgentContext.ArtifactRef(revised.id(),2,null)));
        assertEquals(0,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
        assertEquals(2,workspace().path("steps").get(0).path("scenes").get(0).path("sourceRef").path("version").asInt());
        verify(gateway,times(4)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】超过20份作品且无手动selection时，当前逐幕父步的分镜准确版本仍出现在快照，快照不写状态。
    // 【怎么算红】删artifacts的当前scene来源pin，最近20作品窗口将挤掉原分镜，此断言变红。
    @Test void activeStoryboardRemainsPinnedBeyondArtifactWindowWithoutSelection() throws Exception {
        var b=board(4);start(b);var s=store.owned(conversation,1,false);
        for(int i=0;i<25;i++)store.artifact(s,"later:"+i,null,"SCRIPT","后续作品","内容");
        assertTrue(workspace().path("selection").isNull());
        var facts=db.queryForList("SELECT * FROM agent_plan_scene ORDER BY ordinal_no");
        var snapshot=app.snapshot(1,conversation);
        assertTrue(snapshot.artifacts().stream().anyMatch(a->a.id().equals(b.id())&&a.version()==1));
        assertTrue(snapshot.artifacts().size()<=20);
        assertEquals(facts,db.queryForList("SELECT * FROM agent_plan_scene ORDER BY ordinal_no"));
        verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
}
