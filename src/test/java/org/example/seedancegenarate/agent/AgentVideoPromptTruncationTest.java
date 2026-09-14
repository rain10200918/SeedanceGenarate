package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.service.llm.LlmChannelException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL/checkpoints and runtime; model and paid generation boundaries are injected fakes. */
class AgentVideoPromptTruncationTest extends AgentVideoPreparationRuntimeTest {
    LlmChannelException truncated() {
        return LlmChannelException.terminal("private provider response must not escape",null)
                .classified("MODEL_OUTPUT_TRUNCATED",false,200,null,2207,12288);
    }
    void scenes(int total) {
        plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),"bound-step",java.util.stream.IntStream.rangeClosed(1,total)
                .mapToObj(i->new AgentVideoPromptPreparation.Scene("s"+i,i,json.nullNode(),quote,json.createObjectNode(),"guide")).toList(),4000);
    }
    int attempts() {return db.queryForObject("SELECT attempt_count FROM agent_model_recovery WHERE call_id IS NOT NULL",Integer.class);}

    // 【测什么】12幕的第3幕截断，重启及旧作业重放仍只修复该幕；前两幕保留，12幕齐备才审批一次。
    // 【怎么算红】恢复排除videoPreparation、删expectedJob身份或忽略成功检查点时，状态/幕序列/审批次数断言变红。
    @Test void twelveScenesResumeOnlyThirdAcrossRestartAndReplay() {
        videoSetup();scenes(12);var invoked=new ArrayList<Integer>();var repairs=new ArrayList<Boolean>();
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            int ordinal=((AgentVideoPromptPreparation.Scene)a.getArgument(2)).ordinal();
            boolean repair=((AgentContext)a.getArgument(0)).outputRepair();invoked.add(ordinal);repairs.add(repair);
            if(ordinal==3&&!repair)throw truncated();return "scene-"+ordinal;
        });
        startVideo();run();run();var old=next();run();
        var snapshot=app.snapshot(1,id);var progress=snapshot.state().preparation();
        assertEquals("WAITING_RETRY",snapshot.turn().status());assertEquals(2,progress.path("completed").asInt());
        assertEquals(12,progress.path("total").asInt());assertEquals(3,progress.path("currentSceneOrdinal").asInt());
        assertEquals(1,progress.path("truncationRepairs").asInt());assertEquals(1,progress.path("attemptCount").asInt());
        assertEquals("MODEL_OUTPUT_TRUNCATED",progress.path("errorCode").asText());assertTrue(progress.hasNonNull("retryAt"));
        assertFalse(snapshot.turn().error().contains("private provider"));
        verify(generation,never()).awaitApproval(any(),any(),any(),any());assertEquals(0,count("agent_approval"));
        store=new AgentStore(db,json);runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,generation,diagnostics,models);
        ReflectionTestUtils.setField(runtime,"videoPrompts",preparation);
        runtime.execute(old,true);assertEquals(List.of(1,2,3),invoked);
        retryDue();var repairJob=next();run();runtime.execute(repairJob,true);
        assertEquals(List.of(1,2,3,3),invoked);assertEquals(List.of(false,false,false,true),repairs);
        assertEquals(3,app.snapshot(1,id).state().preparation().path("completed").asInt());
        assertEquals(0,app.snapshot(1,id).state().preparation().path("attemptCount").asInt());
        for(int i=4;i<=12;i++)run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        assertTrue(app.snapshot(1,id).state().preparation().isNull());
        assertEquals("scene-1",db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint WHERE ordinal=1",String.class));
        assertEquals("scene-2",db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint WHERE ordinal=2",String.class));
        assertEquals(13,invoked.size());verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】再次截断即暂停，保留具体幕/原因和一次修复记录，无第三次调用或审批。
    // 【怎么算红】去掉truncationRepairs==0门槛，第二次失败会再次WAITING_RETRY而变红。
    @Test void secondTruncationSuspendsWithDurableDiagnostic() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(truncated());
        startVideo();run();retryDue();var job=next();run();runtime.execute(job,true);
        var snapshot=app.snapshot(1,id);var p=snapshot.state().preparation();
        assertEquals("SUSPENDED",snapshot.turn().status());assertEquals(2,attempts());
        assertEquals(1,p.path("currentSceneOrdinal").asInt());assertEquals(2,p.path("attemptCount").asInt());
        assertEquals(1,p.path("truncationRepairs").asInt());assertEquals("MODEL_OUTPUT_TRUNCATED",p.path("errorCode").asText());
        assertFalse(p.hasNonNull("retryAt"));assertTrue(snapshot.turn().error().contains("第 1 幕"));
        assertNull(db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint",String.class));
        verify(preparation,times(2)).prepareScene(any(),any(),any());verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】两幕分别截断，重试键互不冲突，旧幕重试不能执行新幕。
    // 【怎么算红】使用不含ordinal的call:model-retry:attempt键，第二幕入队唯一键冲突或旧作业调用模型。
    @Test void consecutiveTruncationsHaveSceneScopedJobKeys() {
        videoSetup();scenes(2);
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            if(!((AgentContext)a.getArgument(0)).outputRepair())throw truncated();return "complete";
        });
        startVideo();run();retryDue();var first=next();run();run();retryDue();var second=next();
        assertNotEquals(first.getBizKey(),second.getBizKey());runtime.execute(first,true);
        verify(preparation,times(3)).prepareScene(any(),any(),any());run();
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(preparation,times(4)).prepareScene(any(),any(),any());
    }

    // 【测什么】格式修复后又截断，两种资格共用三次总尝试，第三次失败不再扩预算。
    // 【怎么算红】截断恢复重置attempt_count或按错误各设3次时，attempts/终态断言变红。
    @Test void mixedFormatAndTruncationShareAttemptBudget() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(invalidOutput());
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenThrow(truncated());
        startVideo();run();retryDue();run();assertEquals("WAITING_RETRY",app.snapshot(1,id).turn().status());
        retryDue();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(3,attempts());
        var p=app.snapshot(1,id).state().preparation();assertEquals(1,p.path("repairCount").asInt());
        assertEquals(1,p.path("truncationRepairs").asInt());verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】截断后格式修复成功，仍重新生成完整当前幕，按原路径一次审批。
    // 【怎么算红】两种修复共享同一Job键或格式重试丢outputRepair标记时这条变红。
    @Test void truncationThenFormatRepairCanFinishWithinThreeAttempts() {
        videoSetup();
        when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            if(((AgentContext)a.getArgument(0)).outputRepair())throw invalidOutput();throw truncated();
        });
        when(preparation.prepareScene(any(),any(),any(),anyString())).thenAnswer(a->{
            assertTrue(((AgentContext)a.getArgument(0)).outputRepair());return "complete";
        });
        startVideo();run();retryDue();run();retryDue();run();assertEquals(3,attempts());
        assertEquals("WAITING_APPROVAL",app.snapshot(1,id).turn().status());
        verify(generation,times(1)).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】重试入队失败回滚截断修复预留和作业身份，回收原作业仍受原总预算限制。
    // 【怎么算红】defer或enqueue不在同事务，失败后truncation_repairs或expected_job_key断言变红。
    @Test void truncationEnqueueFailureRollsBackAndReclamationDoesNotResetBudget() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(truncated());
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("queue down"))
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        startVideo();var old=next();assertThrows(org.springframework.dao.DataAccessException.class,this::run);
        assertEquals(1,attempts());assertEquals(0,db.queryForObject("SELECT truncation_repairs FROM agent_model_recovery WHERE call_id IS NOT NULL",Integer.class));
        assertEquals(old.getBizKey(),db.queryForObject("SELECT expected_job_key FROM agent_model_recovery WHERE call_id IS NOT NULL",String.class));
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        runtime.execute(old,true);assertEquals(2,attempts());retryDue();run();
        assertEquals(3,attempts());assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】运行期间取消，迟到的截断修复结果不能写检查点/审批，取消后无准备活动投影。
    // 【怎么算红】成功回调不检查current，或progress不筛当前Turn终态时，这条变红。
    @Test void cancelledTruncationRepairCannotSaveLateResult() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            if(!((AgentContext)a.getArgument(0)).outputRepair())throw truncated();
            app.cancel(1,id,app.snapshot(1,id).turn().id());return "late result";
        });
        startVideo();run();retryDue();run();assertEquals("CANCELLED",app.snapshot(1,id).turn().status());
        assertTrue(app.snapshot(1,id).state().preparation().isNull());
        assertNull(db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint",String.class));
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】截断重试前规格hash变化，不复用旧模型结果或进行第二次调用。
    // 【怎么算红】loadOrCreate不检查绑定，会再次调用并可能审批。
    @Test void changedBindingRejectsTruncationRetry() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(truncated());
        startVideo();run();retryDue();plan=new AgentVideoPromptPreparation.Plan("b".repeat(64),plan.batchStepId(),plan.scenes(),plan.perPrompt());
        run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        verify(preparation,times(1)).prepareScene(any(),any(),any());verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】已有三次持久尝试（含进程回收），截断不能再预留修复，具体诊断保留。
    // 【怎么算红】调度时忽略MAX_ATTEMPTS，状态变WAITING_RETRY而失败。
    @Test void exhaustedAttemptCannotReserveTruncationRepair() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            db.update("UPDATE agent_model_recovery SET attempt_count=3 WHERE call_id IS NOT NULL");throw truncated();
        });
        startVideo();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(3,attempts());
        assertEquals(0,app.snapshot(1,id).state().preparation().path("truncationRepairs").asInt());
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }

    // 【测什么】报价阶段尚无准确检查点时，即使抛出同一模型错误也不能重试整个TaskSkill。
    // 【怎么算红】只按错误码放行而不检查未完成幕，会排重试作业。
    @Test void truncationBeforeCheckpointDoesNotRetryTaskSkillQuote() {
        videoSetup();when(video.quote(any(),any())).thenThrow(truncated());startVideo();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(0,count("agent_video_prompt_checkpoint"));
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());verifyNoInteractions(preparation);
    }

    // 【测什么】首幕已保存后第二幕重报价抛同名错误，不能冒充第二幕模型截断或消耗修复资格。
    // 【怎么算红】Runtime仅凭已有检查点判断错误来源，状态会WAITING_RETRY、修复数1而变红。
    @Test void quoteTruncationAfterSavedSceneCannotConsumeModelRepair() {
        videoSetup();scenes(2);startVideo();run();
        when(video.quote(any(),any())).thenThrow(truncated());run();
        var snapshot=app.snapshot(1,id);assertEquals("SUSPENDED",snapshot.turn().status());
        assertEquals("VIDEO_PREPARATION_PRECHECK_FAILED",snapshot.state().preparation().path("errorCode").asText());
        assertEquals(1,snapshot.state().preparation().path("completed").asInt());
        assertEquals(0,snapshot.state().preparation().path("truncationRepairs").asInt());
        assertEquals("prepared",db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint WHERE ordinal=1",String.class));
        assertNull(db.queryForObject("SELECT prompt FROM agent_video_prompt_checkpoint WHERE ordinal=2",String.class));
        verify(preparation,times(1)).prepareScene(any(),any(),any());
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        verify(generation,never()).awaitApproval(any(),any(),any(),any());
    }

    // 【测什么】模型修复中的断线超时维持原暂停规则，不借截断授权扩展自动重试范围。
    // 【怎么算红】将videoPreparation的所有retryable错误放行会再次排队而不是暂停。
    @Test void timeoutAfterTruncationStillSuspends() {
        videoSetup();when(preparation.prepareScene(any(),any(),any())).thenAnswer(a->{
            if(!((AgentContext)a.getArgument(0)).outputRepair())throw truncated();
            throw LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("private timeout"));
        });
        startVideo();run();retryDue();run();assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());assertEquals(2,attempts());
        assertEquals("MODEL_TIMEOUT",app.snapshot(1,id).state().preparation().path("errorCode").asText());
        verify(jobs,times(1)).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
    }

    // 【测什么】只准备第7幕时total=1但ordinal=7，当前幕必须来自检查点而非completed+1。
    // 【怎么算红】progress用已完成数量推导ordinal，显示1而非7，断言变红。
    @Test void projectionUsesSourceOrdinalInsteadOfCompletedCount() {
        videoSetup();plan=new AgentVideoPromptPreparation.Plan("a".repeat(64),null,
                List.of(new AgentVideoPromptPreparation.Scene("source-7",7,json.nullNode(),quote,json.createObjectNode(),"guide")),4000);
        when(preparation.prepareScene(any(),any(),any())).thenThrow(truncated());startVideo();run();
        var p=app.snapshot(1,id).state().preparation();assertEquals(1,p.path("total").asInt());
        assertEquals(0,p.path("completed").asInt());assertEquals(7,p.path("currentSceneOrdinal").asInt());
    }

    // 【测什么】截断诊断能关联真实幕次/调用/尝试和provider token计数，日志不输出失败原因原文。
    // 【怎么算红】删除幕级model failure日志或记录error.reason/raw，关联字段/隐私断言变红。
    @Test void sceneDiagnosticsContainUsageAndIdentityWithoutProviderText() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(AgentRuntime.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            videoSetup();when(preparation.prepareScene(any(),any(),any())).thenThrow(truncated());startVideo();run();
            String call=db.queryForObject("SELECT id FROM agent_skill_call",String.class);
            String failure=logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m->m.contains("Agent video prompt model failure")).findFirst().orElseThrow();
            assertTrue(failure.contains("call="+call));assertTrue(failure.contains("scene=1"));assertTrue(failure.contains("attempt=1"));
            assertTrue(failure.contains("promptTokens=2207"));assertTrue(failure.contains("completionTokens=12288"));
            assertTrue(failure.contains("code=MODEL_OUTPUT_TRUNCATED"));
            assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains("private provider")||e.getThrowableProxy()!=null));
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
