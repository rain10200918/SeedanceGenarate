package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Durable recovery uses real SQL/transactions, no external model or generation provider. */
class AgentModelRecoveryTest extends AgentRuntimeIntegrationTest {
    @BeforeEach void delayedJobs() {
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }
    LlmChannelException timeout() {return LlmChannelException.readTimeout(new HttpTimeoutException("timed out"));}
    AgentDecision done() {return new AgentDecision("RESPOND","完成",null,List.of(),null,null);}
    void due() {db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");}

    LlmChannelException truncated() {return LlmChannelException.terminal("truncated",null)
            .classified("MODEL_OUTPUT_TRUNCATED",false,200,null,100,1500);}

    // 【测什么】20秒寓言截断自动处理原Planner，第二次截断即暂停而非无限重试。
    // 【怎么算红】length立即暂停或允许第二次修复，WAITING_RETRY/调用次数断言失败。
    @Test void truncationRepairsOnceAndRejectsRepeatedTruncation() {
        when(planner.decide(any())).thenThrow(truncated());
        app.send(1,id,send("m1","20秒寓言"));run();
        assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        due();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(planner,times(2)).decide(any());assertEquals(0,count("agent_artifact_version"));
        assertTrue(app.snapshot(1,id).turn().error().contains("输出额度"));
    }

    // 【测什么】重启后保留修复标记，修复超时沿第三次原请求重试，总额度不重置。
    // 【怎么算红】仅内存保存标记/timeout清空标记/新增第四次尝试都会失败。
    @Test void repairSurvivesRestartAndTimeoutWithinTotalBudget() {
        when(planner.decide(any())).thenThrow(truncated()).thenThrow(timeout()).thenReturn(done());
        var turn=app.send(1,id,send("m1","20秒寓言")).turn();var old=next();run();
        assertTrue(app.snapshot(1,id).turn().error().contains("自动调整"));
        store=new AgentStore(db,json);runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        runtime.execute(old,false);verify(planner,times(1)).decide(any());
        due();run();due();run();
        var contexts=org.mockito.ArgumentCaptor.forClass(org.example.seedancegenarate.agent.model.AgentContext.class);
        verify(planner,times(3)).decide(contexts.capture());
        assertEquals(List.of(false,true,true),contexts.getAllValues().stream().map(org.example.seedancegenarate.agent.model.AgentContext::outputRepair).toList());
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals(3,store.modelRecovery().get(store.turn(turn.id()),null).attempts());
        assertEquals(1,store.modelRecovery().get(store.turn(turn.id()),null).truncationRepairs());
    }

    // 【测什么】截断修复等待时取消不再调用；修复中的400配置错误直接暂停。
    // 【怎么算红】取消仍被领取或400走瞬时retry时调用数/状态失败。
    @Test void repairCancellationAndPermanentFailureDoNotLoop() {
        when(planner.decide(any())).thenThrow(truncated());
        var t=app.send(1,id,send("m1","创作")).turn();run();app.cancel(1,id,t.id());due();run();
        verify(planner,times(1)).decide(any());assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        doThrow(truncated()).doThrow(LlmChannelException.terminal("bad request",null)
                .classified("MODEL_INVALID_REQUEST",false,400,null,null,null)).when(planner).decide(any());
        app.send(1,id,send("m2","重新创作"));run();due();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());verify(planner,times(3)).decide(any());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }

    // 【测什么】截断修复与延迟作业入队同事务回滚，重领后只消耗原总预算。
    // 【怎么算红】修复标记提前提交则回滚后仍为1，重领会误判已修复耗尽。
    @Test void repairEnqueueRollbackDoesNotSpendRepairAllowance() {
        when(planner.decide(any())).thenThrow(truncated());var t=app.send(1,id,send("m1","创作")).turn();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("enqueue failed"))
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        assertThrows(org.springframework.dao.DataAccessException.class,this::run);
        assertEquals(0,store.modelRecovery().get(store.turn(t.id()),null).truncationRepairs());
        delayedJobs();run();assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        due();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(1,store.modelRecovery().get(store.turn(t.id()),null).truncationRepairs());
        verify(planner,times(3)).decide(any());
    }

    // 【测什么】文本Skill截断只修原call，成功后重放不落两份作品，下一Planner不继承修复。
    // 【怎么算红】重做Planner或创建新call/成功后未挡重放/修复标记泄漏到下一操作时失败。
    @Test void textTruncationRepairsSameCallAndDoesNotReplaySuccess() {
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode())).thenReturn(done());
        when(skill.execute(any(),any())).thenThrow(truncated()).thenReturn(new SkillResult("SCRIPT","寓言","正文",null));
        app.send(1,id,send("m1","20秒寓言"));run();var first=next();run();
        String call=store.read(first.getPayload()).path("callId").asText();
        assertEquals("WAITING_RETRY",store.call(call).status());due();var retry=next();run();runtime.execute(retry,true);run();
        assertEquals(1,count("agent_skill_call"));assertEquals(1,count("agent_artifact_version"));
        var contexts=org.mockito.ArgumentCaptor.forClass(org.example.seedancegenarate.agent.model.AgentContext.class);
        verify(skill,times(2)).execute(contexts.capture(),any());
        assertEquals(List.of(false,true),contexts.getAllValues().stream().map(org.example.seedancegenarate.agent.model.AgentContext::outputRepair).toList());
        verify(planner,times(2)).decide(argThat(c->!c.outputRepair()));
    }

    // 【测什么】规划超时保存等待状态并只重试原step，成功后结束且不重投技能。
    // 【怎么算红】把模型错误恢复换回terminalFailure，WAITING_RETRY和两次调用断言失败。
    @Test void timeoutPersistsDelayAndResumesSameDecision() {
        when(planner.decide(any())).thenThrow(timeout()).thenReturn(done());
        var turn=app.send(1,id,send("m1","创作")).turn();run();
        assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        assertEquals(0,store.turn(turn.id()).step());assertEquals(0,count("agent_skill_call"));
        assertThrows(BusinessException.class,()->app.send(1,id,send("m2","再来")));
        due();run();assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        verify(planner,times(2)).decide(any());assertEquals(1,count("agent_turn"));
    }
    // 【测什么】预算跨Store/Runtime重建保留，三次失败暂停，人工恢复才开启新组。
    // 【怎么算红】不持久计数或耗尽时继续入队，SUSPENDED和调用次数断言失败。
    @Test void restartDoesNotResetThreeAttemptBudget() {
        when(planner.decide(any())).thenThrow(timeout());app.send(1,id,send("m1","创作"));run();
        store=new AgentStore(db,json);runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        due();run();due();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(3,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        doReturn(done()).when(planner).decide(any());app.send(1,id,send("resume","继续"));run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】等待中的取消/删除让迟到重试失效。
    // 【怎么算红】WAITING_RETRY未加入busy/cancel范围或current不核epoch，将重新调用模型。
    @Test void cancellationAndDeletionDoNotReviveRetry() {
        when(planner.decide(any())).thenThrow(timeout());var t=app.send(1,id,send("m1","创作")).turn();run();
        app.cancel(1,id,t.id());due();run();assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM agent_model_recovery WHERE turn_id=?",String.class,t.id()));
        verify(planner,times(1)).decide(any());
        var deleted=app.send(1,id,send("m2","新创作")).turn();run();app.delete(1,id);due();run();
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM agent_model_recovery WHERE turn_id=?",String.class,deleted.id()));
        verify(planner,times(2)).decide(any());
    }
    // 【测什么】纯文本Skill失败只重试原call，旧Planner作业和成功Skill重放都不能新增作品。
    // 【怎么算红】移除recovery状态/jobKey守卫或每次重试创建call，调用/作品数量断言失败。
    @Test void textRetryKeepsCallAndRejectsOldPlannerAndSuccessfulReplay() {
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
        when(skill.execute(any(),any())).thenThrow(timeout()).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));
        app.send(1,id,send("m1","创作"));var oldPlanner=next();run();var firstCall=next();run();
        String call=store.read(firstCall.getPayload()).path("callId").asText();
        assertEquals("WAITING_RETRY",store.call(call).status());
        runtime.execute(oldPlanner,false);runtime.execute(firstCall,true);
        verify(planner,times(1)).decide(any());verify(skill,times(1)).execute(any(),any());
        due();var retry=next();run();runtime.execute(retry,true);
        assertEquals("SUCCEEDED",store.call(call).status());assertEquals(1,count("agent_skill_call"));assertEquals(1,count("agent_artifact_version"));
        verify(skill,times(2)).execute(any(),any());
    }
    // 【测什么】不可重试模型错误暂停，原样重发无益，不应消耗剩余两次额度。
    // 【怎么算红】把所有LlmChannelException都retry或terminalFailure，SUSPENDED/无延迟作业断言失败。
    @Test void permanentModelFailureSuspendsWithoutBlindRetry() {
        when(planner.decide(any())).thenThrow(LlmChannelException.failoverable("invalid model",null));
        app.send(1,id,send("m1","创作"));run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        assertFalse(app.snapshot(1,id).turn().error().contains("调整需求"));
    }
    // 【测什么】TaskSkill即使抛模型暂时异常也不能纳入文本自动重试或重复创建费用审批。
    // 【怎么算红】删除非TaskSkill限制，WAITING_RETRY/延迟入队就会出现。
    @Test void taskSkillsAreNeverAutomaticallyRetried() {
        TaskSkill task=mock(TaskSkill.class);
        when(task.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","fake task",json.createObjectNode()));
        when(task.quote(any(),any())).thenThrow(timeout());
        skills=new SkillRegistry(List.of(task));runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
        app.send(1,id,send("m1","创作"));run();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(0,count("agent_approval"));
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());verify(task,times(1)).quote(any(),any());
    }
    // 【测什么】延迟入队失败与WAITING_RETRY状态同事务回滚，原作业可恢复且不丢预算。
    // 【怎么算红】先单独提交waiting状态再入队，rollback后会遗留WAITING_RETRY或计数归零。
    @Test void failedDelayedEnqueueRollsBackWaitingState() {
        when(planner.decide(any())).thenThrow(timeout());app.send(1,id,send("m1","创作"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db unavailable"))
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        assertThrows(org.springframework.dao.DataAccessException.class,this::run);
        assertEquals("RUNNING",app.snapshot(1,id).turn().status());
        assertEquals(1,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        delayedJobs();run();assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        assertEquals(2,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
    }
    // 【测什么】长停机后只恢复同一模型尝试预算，旧deadline不错误地结束整个计划。
    // 【怎么算红】在WAITING_RETRY恢复前保留无条件expired判断，会FAILED且模型只调用一次。
    @Test void expiredTurnDeadlineDoesNotKillDelayedModelRecovery() {
        when(planner.decide(any())).thenThrow(timeout()).thenReturn(done());app.send(1,id,send("m1","创作"));run();
        db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(HOUR,-1,NOW())");due();run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());verify(planner,times(2)).decide(any());
    }
    // 【测什么】即使重复通知早到也不能提前消耗调用次数；已有作业保留供正常到期领取。
    // 【怎么算红】删掉next_retry_at门槛，第二次run会调用Planner并结束任务。
    @Test void earlyNotificationDoesNotSpendAttempt() {
        when(planner.decide(any())).thenThrow(timeout()).thenReturn(done());app.send(1,id,send("m1","创作"));run();run();
        verify(planner,times(1)).decide(any());assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        assertEquals(1,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
        due();run();assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】崩溃/事务异常后原作业重领也消耗持久化尝试，不能靠重启无限调用模型。
    // 【怎么算红】不在外调前计数或移除begin上限，第四次仍抛异常且Turn无法暂停。
    @Test void crashReclaimsStopBeforeFourthInvocation() {
        when(planner.decide(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("commit unknown"));
        app.send(1,id,send("m1","创作"));
        for(int attempt=0;attempt<3;attempt++)assertThrows(org.springframework.dao.DataAccessException.class,this::run);
        run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(planner,times(3)).decide(any());assertEquals(3,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
    }
    // 【测什么】等重试期间工作区改版不能继续旧规划；外调回来后也必须拒绝旧结果。
    // 【怎么算红】去掉modelRecovery.matches的workspace比较，两次旧上下文会调用模型或落消息。
    @Test void changedWorkspaceFencesRetryAndLateModelResult() {
        when(planner.decide(any())).thenThrow(timeout());app.send(1,id,send("m1","创作"));run();
        var s=store.owned(id,1,false);var workspace=store.workspace(s);workspace.put("version",workspace.path("version").asLong()+1);store.saveWorkspace(s,workspace);
        due();run();verify(planner,times(1)).decide(any());assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        doAnswer(a->{var w=store.workspace(s);w.put("version",w.path("version").asLong()+1);store.saveWorkspace(s,w);return done();}).when(planner).decide(any());
        app.send(1,id,send("continue","继续"));run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(0,count("agent_decision"));
    }
}
