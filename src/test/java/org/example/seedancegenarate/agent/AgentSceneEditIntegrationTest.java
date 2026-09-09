package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentSceneEditApplication;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.agent.model.AgentDecisionDiagnostics;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSceneEditIntegrationTest extends AgentSceneIntegrationTest {
    CreativeSkill editor;
    @org.junit.jupiter.api.BeforeEach void migrateEdit() throws Exception {
        String sql=new String(getClass().getResourceAsStream("/db/migration/V48__agent_scene_edit.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(db.getDataSource());
        editor=mock(CreativeSkill.class);
        when(editor.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation","1","edit",json.createObjectNode(),"STORYBOARD"));
        when(editor.execute(any(),any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0);var ref=c.selection();var s=store.owned(conversation,1,false);
            var original=store.artifactVersion(s,ref.artifactId(),ref.version());
            var data=original.data().deepCopy();
            for(var scene:data.path("scenes"))if(ref.sceneId().equals(scene.path("sceneId").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)scene).put("visual","实验室");
            return new SkillResult("STORYBOARD",original.title(),"修改后的分镜",original.id(),data,ref);
        });
        var models=mock(AgentModelGateway.class);when(models.channelBinding(anyString())).thenReturn("a".repeat(64));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(java.util.List.of(skill,editor)),json,generation,mock(AgentDecisionDiagnostics.class),models);
    }
    AgentSceneEditApplication edits(){return new AgentSceneEditApplication(store,approvals,tx,jobs);}
    void revise(String artifact,int version,String scene,String key){edits().apply(1,conversation,new AgentSceneEditApplication.Command(key,workspace().path("version").asLong(),new AgentContext.ArtifactRef(artifact,version,scene),"改成实验室"));}
    void editRuns(){for(int i=0;i<6&&!"COMPLETED".equals(workspace().path("sceneEdit").path("status").asText());i++)run();assertEquals("COMPLETED",workspace().path("sceneEdit").path("status").asText());}
    // 【测什么】已完成四幕后只改第三幕，其他V1成功作品保持引用，第三幕V2成功即可确定性交付新计划。
    // 【怎么算红】completion强制所有scene source版本等于最新分镜，保留的三幕V1会错误暂停最终计划。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void completionRetainsUnchangedSceneVersionsAfterLocalEdit(boolean tamperedOtherScene) throws Exception {
        allFourScenesContinueWithSeparateApprovals();
        var board=app.snapshot(1,conversation).artifacts().stream().filter(a->"STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        revise(board.id(),1,"s3","final-edit");editRuns();
        assertEquals(1,workspace().path("steps").get(1).path("scenes").get(0).path("sourceRef").path("version").asInt());
        assertEquals(2,workspace().path("steps").get(1).path("scenes").get(2).path("sourceRef").path("version").asInt());
        if(tamperedOtherScene) {
            var changed=store.artifactVersion(store.owned(conversation,1,false),board.id(),2).data().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("scenes").get(0)).put("visual","并非原场景");
            db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=? AND version_no=2",changed.toString(),board.id());
        }
        run();run();approval=db.queryForObject("SELECT id FROM agent_approval WHERE status='PENDING'",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("edited-task");run();
        when(gateway.read(1,"edited-task")).thenReturn(new AgentGenerationGateway.TaskView("edited-task","SUCCESS","IMAGE",null,false,false,null));wake();run();
        assertEquals(tamperedOtherScene?"SUSPENDED":"SUCCEEDED",workspace().path("executionStatus").asText());
        assertEquals(tamperedOtherScene?"SUSPENDED":"COMPLETED",app.snapshot(1,conversation).turn().status());
        verify(gateway,times(5)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】按当前幕基准修改创建新计划，原key重放不重复，其他幕成功事实保持。
    // 【怎么算红】不实现持久改幕命令或移除幂等判断，新计划/命令计数将不符。
    @Test void revisionIsDurableAndIdempotent() {
        var b=board(4); start(b);
        var command=new AgentSceneEditApplication.Command("edit",workspace().path("version").asLong(),
                new AgentContext.ArtifactRef(b.id(),1,"s3"),"第三幕改成实验室");
        var edits=new AgentSceneEditApplication(store,approvals,tx,jobs);
        edits.apply(1,conversation,command); edits.apply(1,conversation,command);
        assertEquals(2,count("agent_plan"));
        assertEquals(1,count("agent_scene_edit"));
        assertEquals("EDITING",workspace().path("sceneEdit").path("status").asText());
    }
    // 【测什么】改稿真正执行原Skill并只改第三幕，后续再次编辑使用新版基准而不是历史媒体source。
    // 【怎么算红】不放行精准改稿结果、错误重置整板或editReference沿旧版本，该测试失败。
    @Test void selectedSceneOnlyAndSecondEditUsesLatestBaseline() throws Exception {
        var b=board(4);start(b);revise(b.id(),1,"s3","first");editRuns();
        var s=store.owned(conversation,1,false);var latest=store.artifactVersion(s,b.id(),2);
        assertEquals(b.data().path("scenes").get(0),latest.data().path("scenes").get(0));
        assertEquals("实验室",latest.data().path("scenes").get(2).path("visual").asText());
        assertEquals(2,workspace().path("steps").get(1).path("scenes").get(0).path("editReference").path("version").asInt());
        revise(b.id(),2,"s1","second");editRuns();
        assertEquals(3,store.artifactVersion(s,b.id(),3).version());
        verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
    // 【测什么】未改第一幕生成期间改第三幕，持久等待；旧Task成功后受控保留其原source并自动编辑。
    // 【怎么算红】立即编辑、旧结果推进新行、等待消耗Planner或不自动恢复，这些断言失败。
    @Test void unrelatedInflightIsAwaitedAndHarvestedWithoutRepurchase() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();
        revise(b.id(),1,"s3","wait-edit");clearInvocations(planner);run();
        assertEquals("WAITING_TASK",app.snapshot(1,conversation).turn().status());verifyNoInteractions(planner);
        db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(HOUR,-2,NOW()) WHERE id=?",app.snapshot(1,conversation).turn().id());
        when(gateway.read(1,"tsk-1")).thenReturn(success(false));wake();run();editRuns();
        var first=workspace().path("steps").get(1).path("scenes").get(0);
        assertEquals("SUCCEEDED",first.path("status").asText());assertEquals(1,first.path("sourceRef").path("version").asInt());
        assertEquals(2,first.path("editReference").path("version").asInt());
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】目标幕旧Task迟到成功仅存历史，不能让替代目标成功或复用旧审批。
    // 【怎么算红】收割等待集合包含目标幕，或recordMedia去掉fencing，替代目标会错误成功。
    @Test void targetLateResultNeverCompletesReplacement() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();revise(b.id(),1,"s1","target");editRuns();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false));wake();
        while(!"AGENT_GENERATION".equals(next().getJobType()))run();run();
        assertNotEquals("SUCCEEDED",workspace().path("steps").get(1).path("scenes").get(0).path("status").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE task_id='tsk-1'",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】旧非目标失败明确暂停，人工恢复不重新购买；删除后的等待也不恢复。
    // 【怎么算红】collect把失败视为就绪、due忽略归属/取消，调用数或状态断言失败。
    @Test void unrelatedFailureAndDeletionNeverResubmit() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();revise(b.id(),1,"s3","wait-fail");run();
        when(gateway.read(1,"tsk-1")).thenReturn(new AgentGenerationGateway.TaskView("tsk-1","FAILED","IMAGE",null,false,false,null));wake();run();
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("resume","继续","local"));run();
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());edits().reconcile();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】非法、越权、过期版本与相同key不同内容不能创建第二个修改。
    // 【怎么算红】删workspace CAS、owned、请求hash判断，assertThrows或命令计数变红。
    @Test void commandGuardsRejectStaleAndConflictingInputs() {
        var b=board(4);start(b);long version=workspace().path("version").asLong();
        var command=new AgentSceneEditApplication.Command("key",version,new AgentContext.ArtifactRef(b.id(),1,"s3"),"改成夜晚");
        assertThrows(BusinessException.class,()->edits().apply(2,conversation,command));
        assertThrows(BusinessException.class,()->edits().apply(1,conversation,new AgentSceneEditApplication.Command("empty",version,command.reference()," ")));
        edits().apply(1,conversation,command);
        assertThrows(BusinessException.class,()->edits().apply(1,conversation,new AgentSceneEditApplication.Command("key",version,command.reference(),"不同")));
        assertThrows(BusinessException.class,()->edits().apply(1,conversation,new AgentSceneEditApplication.Command("stale",version,command.reference(),"旧状态")));
        assertEquals(1,count("agent_scene_edit"));
    }
    // 【测什么】图片已全完成、视频未初始化时，两个媒体阶段仅目标失效，旧分镜步骤引用仍V1，新费用单独批准。
    // 【怎么算红】只复制首个媒体组、把原分镜结果改新版或复用目标旧结果，断言失败。
    @Test void bothMediaStagesKeepOtherResultsAndRequireFreshApproval() throws Exception {
        allReusedImagesSkipToFollowingVideoSceneInSameResume();
        var s=store.owned(conversation,1,false);var b=store.artifacts(s).stream().filter(a->"STORYBOARD".equals(a.type())).findFirst().orElseThrow();
        revise(b.id(),1,"s3","both");editRuns();
        var steps=workspace().path("steps");assertEquals(4,steps.size());
        assertEquals(1,steps.get(1).path("artifactRef").path("version").asInt(),"原完成分镜步骤仍引用原版");
        assertEquals("SUCCEEDED",steps.get(2).path("scenes").get(0).path("status").asText());
        assertEquals(1,steps.get(2).path("scenes").get(0).path("sourceRef").path("version").asInt());
        for(int i:new int[]{2,3}) {
            assertNotEquals("SUCCEEDED",steps.get(i).path("scenes").get(2).path("status").asText());
            assertFalse(steps.get(i).path("scenes").get(2).hasNonNull("artifactRef"));
            assertEquals(2,steps.get(i).path("scenes").get(2).path("sourceRef").path("version").asInt());
        }
        run();run();assertEquals("WAITING_APPROVAL",app.snapshot(1,conversation).turn().status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='PENDING'",Integer.class));
        verify(gateway,times(4)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】等待时重建Store/应用可恢复；取消或归档后due不复活也不堵住待恢复队列。
    // 【怎么算红】due缺active/归档条件或依赖内存等待，读取/取消断言失败。
    @Test void restartRestoresWaitingButCancelledWaitIsNotDue() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1");run();revise(b.id(),1,"s3","restart");run();
        var reloaded=new org.example.seedancegenarate.agent.persistence.AgentStore(db,json);
        assertEquals(1,reloaded.plans().edits().due().size());
        assertEquals("WAITING_TASK",reloaded.workspace(reloaded.owned(conversation,1,false)).path("sceneEdit").path("status").asText());
        app.cancel(1,conversation,app.snapshot(1,conversation).turn().id());
        assertTrue(reloaded.plans().edits().due().isEmpty());edits().reconcile();
        app.delete(1,conversation);assertTrue(reloaded.plans().edits().due().isEmpty());
        when(gateway.read(1,"tsk-1")).thenReturn(success(false));wake();run();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】旧非目标SUBMITTING响应不明时接收修改并等待，findAccepted收口后只执行一次原Task。
    // 【怎么算红】SUBMITTING被取消、误当无Task可重投，提交次数/等待断言变红。
    @Test void submittingUncertaintyWaitsForOriginalReconciliation() throws Exception {
        var b=board(4);start(b);run();run();approval=db.queryForObject("SELECT id FROM agent_approval",String.class);approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenThrow(new IllegalStateException("lost response"));run();
        assertEquals("SUBMITTING",state());revise(b.id(),1,"s3","uncertain");run();
        assertEquals("WAITING_TASK",app.snapshot(1,conversation).turn().status());
        when(gateway.findAccepted(anyLong(),anyString())).thenReturn("tsk-1");wake();run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false));wake();run();editRuns();
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】锁前读取WAITING记录后另一个处理已完成，锁后重读不能把已完成编辑倒退。
    // 【怎么算红】删wake锁后重读Edit，stale WAITING状态会被collect写回EDITING/QUEUED。
    @Test void staleWakeMustRereadAfterSessionLock() throws Exception {
        var b=board(4);start(b);revise(b.id(),1,"s3","race");
        var session=store.owned(conversation,1,false);var turn=store.turn(session.activeTurnId());
        String edit=workspace().path("sceneEditId").asText();
        db.update("UPDATE agent_scene_edit SET status='WAITING_TASK' WHERE id=?",edit);store.status(turn.id(),"WAITING_TASK",null);
        var spy=spy(store);
        doAnswer(a->{db.update("UPDATE agent_scene_edit SET status='COMPLETED' WHERE id=?",edit);return store.owned(conversation,1,true);}).when(spy).owned(conversation,1,true);
        assertTrue(tx.execute(t->store.plans().edits().wake(spy,edit)).isEmpty());
        assertEquals("COMPLETED",db.queryForObject("SELECT status FROM agent_scene_edit WHERE id=?",String.class,edit));
        assertEquals("WAITING_TASK",store.turn(turn.id()).status());
    }
    // 【测什么】即使编辑Skill返回其他幕变化，Runtime仍拒绝；窄改稿许可不等于任意覆盖。
    // 【怎么算红】去掉validateResultSource其他幕相等检查，就会落下被篡改的V2。
    @Test void editorCannotChangeUnselectedScene() throws Exception {
        var b=board(4);start(b);revise(b.id(),1,"s3","bad-output");
        var bad=b.data().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("scenes").get(0)).put("visual","恶意修改第一幕");
        doReturn(new SkillResult("STORYBOARD",b.title(),"错误修改",b.id(),bad,new AgentContext.ArtifactRef(b.id(),1,"s3"))).when(editor).execute(any(),any());
        for(int i=0;i<3;i++)run();
        assertEquals(1,db.queryForObject("SELECT MAX(version_no) FROM agent_artifact_version WHERE artifact_id=?",Integer.class,b.id()));
        assertEquals("SUSPENDED",workspace().path("sceneEdit").path("status").asText());
    }
    // 【测什么】待修改计划达步骤上限拒绝，不靠每次插入新edit步骤绕过Plan预算。
    // 【怎么算红】删baseline maxPlanSteps限制，命令将成功创建第二份Plan。
    @Test void editRespectsPlanStepLimit() {
        var b=board(4);start(b);
        var limits=new org.example.seedancegenarate.config.AgentRuntimeProperties();limits.setMaxPlanSteps(1);
        var bounded=new org.example.seedancegenarate.agent.persistence.AgentStore(db,json,limits);
        var command=new AgentSceneEditApplication.Command("limit",workspace().path("version").asLong(),new AgentContext.ArtifactRef(b.id(),1,"s3"),"改图");
        assertThrows(BusinessException.class,()->new AgentSceneEditApplication(bounded,approvals,tx,jobs).apply(1,conversation,command));
        assertEquals(1,count("agent_plan"));
    }
}
