package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentVideoPreparationRuntimeTest extends AgentRuntimeIntegrationTest {
    AgentVideoPromptPreparation preparation;
    AgentGenerationRuntime generation;
    TaskSkill video;
    TaskQuote quote;
    AgentVideoPromptPreparation.Plan plan;
    void videoSetup() {
        video=mock(TaskSkill.class);
        when(video.descriptor()).thenReturn(new SkillDescriptor("video-generation","1","video",json.createObjectNode()));
        skills=new SkillRegistry(List.of(video));
        generation=mock(AgentGenerationRuntime.class);
        preparation=mock(AgentVideoPromptPreparation.class);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,generation,diagnostics,models);
        ReflectionTestUtils.setField(runtime,"videoPrompts",preparation);
        quote=new TaskQuote("comfyui","minimax-h3-t2v-hd","H3","VIDEO",json.createObjectNode().put("prompt","prepared"),BigDecimal.ONE,"CNY");
        when(video.quote(any(),any())).thenReturn(quote);
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),null,List.of(new AgentVideoPromptPreparation.Scene("single",1,json.nullNode(),quote,json.createObjectNode(),"guide")),4000);
        when(preparation.preparePlan(any(),any(),isNull())).thenAnswer(a->plan);
        when(preparation.structuredPlan(any())).thenAnswer(a->a.getArgument(0));
        when(preparation.prepareScene(any(),any(),any())).thenReturn("prepared");
        when(preparation.assemble(any(),any())).thenReturn(new AgentVideoPromptPreparation.PreparedQuotes(quote,null));
        doAnswer(a->{
            org.example.seedancegenarate.agent.persistence.AgentRows.Turn t=a.getArgument(1);
            org.example.seedancegenarate.agent.persistence.AgentRows.Call c=a.getArgument(2);
            store.status(t.id(),"WAITING_APPROVAL",null);store.callStatus(c.id(),"WAITING_APPROVAL",null);return null;
        }).when(generation).awaitApproval(any(),any(),any(),any());
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }
    void startVideo() {
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL","准备视频",null,List.of(),"video-generation",json.createObjectNode().put("prompt","猫")));
        app.send(1,id,send("video-prep","制作视频"));run();
    }
    VideoPreparationException invalidOutput() {
        return VideoPreparationException.invalid(VideoPreparationException.ValidationRule.JSON_INVALID,"$").withDiagnosticId("diagnostic-1");
    }
    void retryDue() {db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");}

    // 【测什么】视频Call受理只显示确定的准备阶段，不将Planner未经执行的幕次/时长宣称发布到用户消息。
    // 【怎么算红】将Runtime activity恢复为d.text()，错误生成宣称将出现在消息正文和TEXT part。
    @Test void videoCallPublishesPreparationActivityInsteadOfPlannerGenerationClaim() {
        videoSetup();
        String claim="正在重新生成第一幕视频，12秒";
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",claim,null,List.of(),"video-generation",
                json.createObjectNode().put("prompt","猫")));
        app.send(1,id,send("video-safe-activity","继续创作"));run();
        var message=app.snapshot(1,id).messages().stream()
                .filter(m->m.parts().toString().contains("skill_call")).findFirst().orElseThrow();
        assertEquals("正在准备视频生成规格与提示词，完成后请确认费用。",message.parts().get(0).path("text").asText());
        assertFalse(message.parts().toString().contains(claim));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE role='ASSISTANT' AND content=?",Integer.class,claim));
        verifyNoInteractions(preparation);verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】失败幕后仅自动修正该幕，DB重建Runtime和旧Job重放均不重做成功幕，不重复审批。
    // 【怎么算红】删除repair_count/expected_job_key守卫或忽略检查点，模型次数、单次审批或保存进度断言失败。
    @Test void invalidSceneRepairsOnceAcrossRuntimeRecreationAndOldJobReplay() {
        videoSetup();
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),"bound-step",java.util.stream.IntStream.rangeClosed(1,2)
                .mapToObj(i->new AgentVideoPromptPreparation.Scene("s"+i,i,json.nullNode(),quote,json.createObjectNode(),"guide")).toList(),4000);
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            if(((AgentVideoPromptPreparation.Scene)a.getArgument(2)).ordinal()==2)throw invalidOutput();
            return "passed-first";
        });
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenReturn("repaired-second");
        startVideo();run();var failedJob=next();run();
        assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        assertEquals(1,app.snapshot(1,id).state().preparation().path("completed").asInt());
        assertEquals(1,db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint WHERE ordinal=2",Integer.class));
        assertEquals("JSON_INVALID",db.queryForObject("SELECT validation_code FROM agent_video_prompt_checkpoint WHERE ordinal=2",String.class));
        assertTrue(app.snapshot(1,id).messages().stream().anyMatch(message->message.parts().toString().contains("VIDEO_PROMPT_PREPARATION")));
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
        store=new org.example.seedancegenarate.agent.persistence.AgentStore(db,json);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,generation,diagnostics,models);
        ReflectionTestUtils.setField(runtime,"videoPrompts",preparation);
        runtime.execute(failedJob,true);retryDue();var retry=next();run();runtime.execute(retry,true);
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(preparation,times(2)).prepareScene(any(),any(),any());
        verify(preparation,times(1)).prepareScene(any(),any(),argThat(s->s.ordinal()==2),eq(VideoPreparationException.ValidationRule.JSON_INVALID.repairHint()));
        verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
        assertEquals(1,count("agent_skill_call"));
        assertEquals("passed-first",db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint WHERE ordinal=1",String.class));
    }

    // 【测什么】唯一修正仍无效会暂停，详细诊断可查，且不创建审批和第三次模型调用。
    // 【怎么算红】修正次数无上限或失败先审批，WAITING_RETRY/费用调用断言失败。
    @Test void failedRepairSuspendsWithoutThirdInvocationOrApproval() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenThrow(invalidOutput());
        startVideo();run();retryDue();var retry=next();run();runtime.execute(retry,true);
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals("SUSPENDED",db.queryForObject("SELECT status FROM agent_skill_call",String.class));
        assertEquals(1,db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint",Integer.class));
        assertEquals(2,db.queryForObject("SELECT attempt_count FROM agent_model_recovery WHERE call_id IS NOT NULL",Integer.class));
        assertTrue(app.snapshot(1,id).turn().error().contains("请检查准备诊断后恢复"));
        assertFalse(app.snapshot(1,id).turn().error().contains("请调整规格"));
        verify(jobs,times(1)).enqueueDelayed(eq(AgentRuntime.SKILL_JOB),anyString(),anyString(),anyLong());
        verify(preparation,times(1)).prepareScene(any(),any(),any(),anyString());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】用户停止后迟到的修正结果不能写入成功检查点或创建批准单。
    // 【怎么算红】去掉修正结束后的current/lease检查，prompt或审批断言失败。
    @Test void cancellationDuringRepairCannotCreateApproval() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenAnswer(a->{
            app.cancel(1,id,app.snapshot(1,id).turn().id());return "late";
        });
        startVideo();run();retryDue();run();
        assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        assertNull(db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint",String.class));
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】输入/模板绑定在修正前变化时旧检查点被拒绝，不调用修正模型或提交批准单。
    // 【怎么算红】忽略binding_hash比较会接受新的输入并生成审批。
    @Test void changedBindingBeforeRepairDoesNotUseOldApprovalScope() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        startVideo();run();retryDue();
        plan=new AgentVideoPromptPreparation.Plan("b".repeat(64),plan.batchStepId(),plan.scenes(),plan.perPrompt());
        run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(preparation,never()).prepareScene(any(),any(),any(),anyString());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】不同幕各自修正有不同持久Job身份，上一幕修正Job重放不能抢占下一幕。
    // 【怎么算红】复用call:model-retry:1将撞job_probe唯一键或让旧Job调用下一幕模型。
    @Test void successiveSceneRepairsHaveDistinctJobKeysAndObservations() {
        videoSetup();
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),"step",java.util.stream.IntStream.rangeClosed(1,2)
                .mapToObj(i->new AgentVideoPromptPreparation.Scene("s"+i,i,json.nullNode(),quote,json.createObjectNode(),"guide")).toList(),4000);
        when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenReturn("repaired");
        startVideo();run();retryDue();var firstRepair=next();run();run();retryDue();
        var secondRepair=next();assertNotEquals(firstRepair.getBizKey(),secondRepair.getBizKey());
        runtime.execute(firstRepair,true);
        verify(preparation,times(1)).prepareScene(any(),any(),any(),anyString());
        run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        assertEquals(2,db.queryForObject("SELECT SUM(repair_count) FROM agent_video_prompt_checkpoint",Integer.class));
        verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】实际入队数据库异常使修正资格、状态和expected job一起回滚，同作业接管后可正确调度。
    // 【怎么算红】reserve/defer若在独立事务提交，异常后repair_count或WAITING_RETRY已持久化。
    @Test void retryEnqueueFailureRollsBackReservationAndCanBeReclaimed() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("enqueue unavailable"))
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        startVideo();var job=next();assertThrows(org.springframework.dao.DataAccessException.class,()->run());
        assertEquals(0,db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint",Integer.class));
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM agent_model_recovery WHERE call_id IS NOT NULL",String.class));
        assertEquals(job.getBizKey(),db.queryForObject("SELECT expected_job_key FROM agent_model_recovery WHERE call_id IS NOT NULL",String.class));
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        runtime.execute(job,true);
        assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        assertEquals(1,db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint",Integer.class));
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }
    // 【测什么】审批前文本超时保留原D083边界，暂停且不创建审批或重投TaskSkill。
    // 【怎么算红】未经确认扩大TaskSkill自动重试时状态或调用数断言失败。
    @Test void preparationTimeoutPreservesExistingNoAutomaticTaskSkillRetry() {
        videoSetup();
        when(preparation.prepareScene(any(),any(),any())).thenThrow(LlmChannelException.readTimeout(new HttpTimeoutException("timeout")));
        startVideo();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
        assertEquals(1,count("agent_skill_call"));
        verify(preparation,times(1)).prepareScene(any(),any(),any());
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }
    // 【测什么】准备成功冻结一次批准单，重放完成作业不能再次调用模型或审批。
    // 【怎么算红】当前状态fence失效导致重复审批时次数断言失败。
    @Test void successfulPreparationDoesNotReplayApproval() {
        videoSetup();startVideo();var job=next();run();runtime.execute(job,true);
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(preparation,times(1)).prepareScene(any(),any(),any());
        verify(generation,times(1)).awaitApproval(any(),any(),any(),eq(quote));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM agent_model_recovery WHERE call_id IS NOT NULL",String.class));
    }
    // 【测什么】准备时用户取消，晚到结果不能创建批准单。
    // 【怎么算红】finishQuote没有重新检查activeTurn/状态时会创建批准单。
    @Test void cancelledPreparationCannotPublishApproval() {
        videoSetup();
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            app.cancel(1,id,app.snapshot(1,id).turn().id());
            return "prepared";
        });
        startVideo();run();
        assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }
    // 【测什么】整批第二幕规格非法时，提示具体幕且不进入模型和审批。
    // 【怎么算红】重新压成BUSINESS_400或先调用模型再校验整批时失败。
    @Test void invalidSceneSuspendsBeforePromptPreparation() {
        videoSetup();
        when(generation.prepareBatch(any(),any())).thenThrow(VideoPreparationException.unsupportedDuration(3,List.of(),5,15).atScene(2));
        startVideo();run();
        var result=app.snapshot(1,id);
        assertEquals("SUSPENDED",result.turn().status());
        assertTrue(result.turn().error().contains("第 2 幕"));
        assertTrue(result.turn().error().contains("3 秒"));
        assertEquals(2,result.state().actionableError().path("sceneOrdinal").asInt());
        assertEquals("VIDEO_DURATION_UNSUPPORTED",result.state().actionableError().path("code").asText());
        verifyNoInteractions(preparation);
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }
    // Four jobs cross the old three-attempt budget; old job replay cannot regenerate a saved scene.
    @Test void fourScenesHaveIndependentBudgetsAndOneApproval() {
        videoSetup();
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),null,java.util.stream.IntStream.rangeClosed(1,4)
                .mapToObj(i->new AgentVideoPromptPreparation.Scene("s"+i,i,json.nullNode(),quote,json.createObjectNode(),"guide")).toList(),4000);
        startVideo();var first=next();run();
        assertEquals(1,app.snapshot(1,id).state().preparation().path("completed").asInt());
        runtime.execute(first,true);
        verify(preparation,times(1)).prepareScene(any(),any(),any());
        run();run();run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(preparation,times(4)).prepareScene(any(),any(),any());
        verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
        assertEquals(4,count("agent_video_prompt_checkpoint"));
    }
    // 【测什么】人工恢复的新call复制同范围成功项，仅调用失败幕，最后仍只审批一次。
    // 【怎么算红】删除未审批同hash复制查询会重复调用第一幕并触发次数断言。
    @Test void resumedCallReusesPreparedScenesWithoutApprovalReplay() {
        videoSetup();
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),"bound-step",java.util.stream.IntStream.rangeClosed(1,2)
                .mapToObj(i->new AgentVideoPromptPreparation.Scene("s"+i,i,json.nullNode(),quote,json.createObjectNode(),"guide")).toList(),4000);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            var scene=(AgentVideoPromptPreparation.Scene)a.getArgument(2);
            if(scene.ordinal()==2&&attempts.getAndIncrement()==0)throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_OUTPUT);
            return "prepared";
        });
        startVideo();run();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(1,app.snapshot(1,id).state().preparation().path("completed").asInt());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
        app.send(1,id,send("resume-preparation","继续"));run();run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(preparation,times(3)).prepareScene(any(),any(),any());
        verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
    }
}
