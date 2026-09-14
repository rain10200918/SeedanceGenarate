package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.persistence.AgentBatchStore;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentBatchRequoteIntegrationTest extends AgentBatchIntegrationTest {
    final VideoSubmitService submissions=mock(VideoSubmitService.class);
    @Override String grant(){return db.queryForObject("SELECT b.id FROM agent_generation_batch b JOIN agent_turn t ON t.id=b.turn_id ORDER BY t.turn_seq DESC,b.epoch DESC,b.step_no DESC LIMIT 1",String.class);}
    AgentBatchRequoteApplication requotes() {
        return new AgentBatchRequoteApplication(store,tx,runtime,gateway,mock(AgentVideoPromptPreparation.class),
                mock(AgentVideoReference.class),submissions,json);
    }
    AgentBatchStore.Batch expired() throws Exception {
        enableBatch();start(board(3));run();run();String id=grant();
        db.update("UPDATE agent_generation_batch SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?",id);
        new AgentBatchApplication(store,tx,jobs).reconcile();
        clearInvocations(planner,gateway,jobs);return store.batches().get(id);
    }
    AgentBatchRequoteApplication.Command command(AgentBatchStore.Batch b,String key) {
        return new AgentBatchRequoteApplication.Command(key,b.version(),b.binding());
    }
    // 【测什么】过期未提交组重新核价有新授权，原组永不过活，同key重放无重复，价格可更新。
    // 【怎么算红】删除新身份创建或requestHash守卫，新批次计数/旧状态/新价格断言必红。
    @Test void requoteCreatesNewUnapprovedGrantAndReplaysWithoutSideEffects() throws Exception {
        var old=expired();var application=requotes();
        when(gateway.quote(anyString(),any())).thenAnswer(a->new TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),"IMAGE",a.getArgument(1),new java.math.BigDecimal("0.20"),"CNY"));
        application.requote(1,conversation,old.id(),command(old,"renew"));
        var fresh=store.batches().get(grant());assertNotEquals(old.id(),fresh.id());
        assertEquals("EXPIRED",store.batches().get(old.id()).status());assertEquals("PENDING",fresh.status());
        assertEquals("WAITING_APPROVAL",store.turn(fresh.turn()).status());assertNotEquals(old.parent(),fresh.parent());
        assertEquals(new java.math.BigDecimal("0.600000"),db.queryForObject("SELECT total_amount FROM agent_generation_batch WHERE id=?",java.math.BigDecimal.class,fresh.id()));
        long messages=count("conversation_message");clearInvocations(gateway);
        application.requote(1,conversation,old.id(),command(old,"renew"));
        assertEquals(2,count("agent_generation_batch"));assertEquals(messages,count("conversation_message"));
        verifyNoInteractions(planner,gateway,jobs);verify(submissions,never()).submitApproved(any(),any());
    }
    // 【测什么】非本人/旧版本/Task关联/不同key并发迟到均不复用授权。
    // 【怎么算红】移除owner、version或整组未提交守卫，assertThrows必红。
    @Test void requoteRejectsStaleOwnerAndAnySubmittedItem() throws Exception {
        var old=expired();var application=requotes();
        assertThrows(BusinessException.class,()->application.requote(2,conversation,old.id(),command(old,"owner")));
        assertThrows(BusinessException.class,()->application.requote(1,conversation,old.id(),new AgentBatchRequoteApplication.Command("stale",1,old.binding())));
        db.update("UPDATE agent_approval SET task_id='submitted-task' WHERE id=(SELECT approval_id FROM agent_batch_item WHERE batch_id=? AND ordinal_no=1)",old.id());
        assertThrows(BusinessException.class,()->application.requote(1,conversation,old.id(),command(old,"submitted")));
        assertEquals(1,count("agent_generation_batch"));verifyNoInteractions(gateway,jobs);
    }

    // 【测什么】两设备不同请求同步核价只能创建一份新报价；同key响应丢失重放不重核价。
    // 【怎么算红】移除整条落地链的当前批次fence（load和create资格复验），并发批次数或成功数断言必红。
    @Test void concurrentDifferentKeysOnlyOneNewGrant() throws Exception {
        var old=expired();var barrier=new java.util.concurrent.CyclicBarrier(2);
        when(gateway.quote(anyString(),any())).thenAnswer(a->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
            return new TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),"IMAGE",a.getArgument(1),quote.amount(),"CNY");
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{
                try {requotes().requote(1,conversation,old.id(),command(old,"parallel"+i));return true;}
                catch(BusinessException e){assertEquals(409,e.getCode());return false;}
            })).toList();
            int successes=0;for(var future:futures)if(future.get(20,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(2,count("agent_generation_batch"));
        } finally {pool.shutdownNow();}
        verifyNoInteractions(planner,jobs);
    }

    // 【测什么】核价时工作区改变或领域已有未回填Task均拒绝，终态非过期不允许续价。
    // 【怎么算红】删除版本二次检查、领域request查询或EXPIRED判定，assertThrows/批次数必红。
    @Test void changedWorkspaceDomainLedgerAndNonExpiredStatesReject() throws Exception {
        var old=expired();
        when(submissions.findByRequestId(anyLong(),anyString())).thenReturn(new org.example.seedancegenarate.entity.VideoTask());
        assertThrows(BusinessException.class,()->requotes().requote(1,conversation,old.id(),command(old,"ledger")));
        reset(submissions);
        for(String state:java.util.List.of("CANCELLED","REJECTED","PARTIAL_FAILED","RUNNING","PENDING")) {
            db.update("UPDATE agent_generation_batch SET status=? WHERE id=?",state,old.id());
            assertThrows(BusinessException.class,()->requotes().requote(1,conversation,old.id(),command(old,"state-"+state)));
        }
        db.update("UPDATE agent_generation_batch SET status='EXPIRED' WHERE id=?",old.id());
        when(gateway.quote(anyString(),any())).thenAnswer(a->{
            var s=store.owned(conversation,1,false);var w=store.workspace(s);w.put("version",w.path("version").asLong()+1);store.saveWorkspace(s,w);
            return new TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),"IMAGE",a.getArgument(1),quote.amount(),"CNY");
        });
        assertThrows(BusinessException.class,()->requotes().requote(1,conversation,old.id(),command(old,"changed")));
        assertEquals(1,count("agent_generation_batch"));verifyNoInteractions(jobs);
    }

    record VideoFixture(AgentBatchStore.Batch batch,AgentVideoPromptPreparationTest f,AgentBatchRequoteApplication application) {}
    VideoFixture expiredVideo(boolean structured) {
        var f=new AgentVideoPromptPreparationTest();var board=board(6);var data=board.data().deepCopy();
        for(var scene:data.path("scenes"))((com.fasterxml.jackson.databind.node.ObjectNode)scene).put("duration",10);
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),board.id());
        workspaceApp().apply(1,conversation,new AgentWorkspaceApplication.Command("video-start",workspace().path("version").asLong(),"GENERATE_SCENES_VIDEO",new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef(board.id(),1,null)));
        var s=store.owned(conversation,1,false);var t=store.turn(s.activeTurnId());store.executionStatus(t.id(),"RUNNING",null);
        var input=json.createObjectNode().put("model",f.model).put("prompt","首幕").put("ratio","1:1").put("duration",10);
        var call=store.call(store.newCall(t,"video-generation","2",input));
        var context=runtime.quoteContext(s,t,call);var quote=f.gateway.quoteVideo(context,input);
        var batch=org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.prepare(f.gateway,context,quote);
        var legacy=f.preparation.preparePlan(context,quote,batch);var plan=structured?f.preparation.structuredPlan(legacy):legacy;
        var prompts=new java.util.LinkedHashMap<String,String>();for(var scene:plan.scenes())prompts.put(scene.key(),f.prompt("scene "+scene.ordinal()));
        store.videoCheckpoints().loadOrCreate(s,t,call,plan);
        for(var scene:plan.scenes())store.videoCheckpoints().save(s,t,call,plan,scene,prompts.get(scene.key()));
        var ready=f.preparation.assemble(plan,prompts);
        String id=store.batches().create(store,s,t,call,ready.batch());
        db.update("UPDATE agent_generation_batch SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?",id);
        new AgentBatchApplication(store,tx,jobs).reconcile();clearInvocations(jobs,planner);
        var application=new AgentBatchRequoteApplication(store,tx,runtime,f.gateway,f.preparation,mock(AgentVideoReference.class),f.submit,json);
        return new VideoFixture(store.batches().get(id),f,application);
    }

    // 【测什么】六幕60秒真实模板及检查点在v3/v4均可复验复用，两次过期重新核价不调用模型。
    // 【怎么算红】用新身份误代原准备绑定或复用时重跑LLM，第二次恢复、正文逐字及零调用断言必红。
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void sixVideoPromptsSurviveTwoExpiriesWithoutModelCalls(boolean structured) throws Exception {
        var v=expiredVideo(structured);var old=v.batch();
        var prompts=db.queryForList("SELECT prompt FROM agent_video_prompt_checkpoint WHERE call_id=? ORDER BY ordinal",String.class,old.parent());
        for(int i=0;i<2;i++) {
            v.application().requote(1,conversation,old.id(),command(old,"video-requote"+i));
            var next=store.batches().get(grant());assertNotEquals(old.id(),next.id());
            assertEquals(prompts,db.queryForList("SELECT prompt FROM agent_video_prompt_checkpoint WHERE call_id=? ORDER BY ordinal",String.class,next.parent()));
            assertEquals(6,store.batchRequotes().items(next).size());
            assertTrue(store.batchRequotes().items(next).stream().allMatch(item->item.quote().inputSnapshot().path("duration").asInt()==10));
            assertEquals(Boolean.TRUE,tx.execute(x->store.batches().beforeStep(store,store.owned(conversation,1,true),store.turn(next.turn()))));
            if(i==0){db.update("UPDATE agent_generation_batch SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?",next.id());new AgentBatchApplication(store,tx,jobs).reconcile();old=store.batches().get(next.id());}
        }
        verifyNoInteractions(v.f().models);verify(v.f().submit,never()).submitApproved(any(),any());verifyNoInteractions(jobs);
    }

    // 【测什么】旧检查点的完整hash不符或模板变化时不能把旧正文包装成新审批。
    // 【怎么算红】移除prompts的binding_hash检查，错误绑定会创建第二批次。
    @Test void videoFullBindingMismatchRejectsWithoutNewApproval() {
        var v=expiredVideo(true);db.update("UPDATE agent_video_prompt_checkpoint SET binding_hash=? WHERE call_id=?","f".repeat(64),v.batch().parent());
        assertThrows(BusinessException.class,()->v.application().requote(1,conversation,v.batch().id(),command(v.batch(),"mismatch")));
        assertEquals(1,count("agent_generation_batch"));verifyNoInteractions(v.f().models,jobs);
    }

    // 【测什么】核价完成到落库前原检查点变化，不能以先前读取的正文越过最终复验。
    // 【怎么算红】删除锁内再次验证准备hash和正文，此测试会错误生成第二份报价。
    @Test void checkpointChangedDuringRequoteIsRejectedAtCommit() {
        var v=expiredVideo(true);var preparation=spy(v.f().preparation);var assembled=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(a->{
            Object result=a.callRealMethod();
            if(assembled.incrementAndGet()==2)db.update("UPDATE agent_video_prompt_checkpoint SET binding_hash=? WHERE call_id=?","f".repeat(64),v.batch().parent());
            return result;
        }).when(preparation).assemble(any(),any());
        var application=new AgentBatchRequoteApplication(store,tx,runtime,v.f().gateway,preparation,mock(AgentVideoReference.class),v.f().submit,json);
        assertThrows(BusinessException.class,()->application.requote(1,conversation,v.batch().id(),command(v.batch(),"checkpoint-race")));
        assertEquals(1,count("agent_generation_batch"));verifyNoInteractions(v.f().models,jobs);
    }

    // 【测什么】新报价必须单独批准才能生成，旧批准重放失败；三幕全部成功后原计划真正完成。
    // 【怎么算红】新grant不接入原审批/屏障或旧EXPIRED仍阻断计划，三Task、三成功幕及COMPLETED断言必红。
    @Test void explicitlyApprovingNewGrantCompletesOriginalPlan() throws Exception {
        var old=expired();requotes().requote(1,conversation,old.id(),command(old,"renew-and-approve"));
        assertThrows(BusinessException.class,()->new AgentBatchApplication(store,tx,jobs).answer(1,conversation,old.id(),new AgentBatchApplication.Answer("old-approve",old.version(),old.binding(),"APPROVE")));
        verify(gateway,never()).submit(anyLong(),any(),anyString());
        accept();run();run();finishItem(1,true);run();finishItem(2,true);finishItem(3,true);
        assertEquals("SUCCEEDED",store.batches().get(grant()).status());assertEquals("EXPIRED",store.batches().get(old.id()).status());
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='SUCCEEDED'",Integer.class));
        verify(gateway,times(3)).submit(anyLong(),any(),anyString());
    }

    // 【测什么】核价前后来源、模型能力或报价服务错误都有明确拒绝，GET不产生报价或写入。
    // 【怎么算红】去掉来源/最终模型复验或在GET里执行核价，批次数、事务和调用断言必红。
    @Test void projectionReadOnlyAndSourceAndModelFences() throws Exception {
        var old=expired();long messages=count("conversation_message");
        var snapshot=app.snapshot(1,conversation);
        assertTrue(snapshot.messages().stream().flatMap(m->java.util.stream.StreamSupport.stream(m.parts().spliterator(),false)).anyMatch(p->p.path("canRequote").asBoolean()));
        assertEquals(messages,count("conversation_message"));verifyNoInteractions(gateway);
        doThrow(BusinessException.conflict("model changed")).when(gateway).validateQuoteSpec(any());
        assertThrows(BusinessException.class,()->requotes().requote(1,conversation,old.id(),command(old,"model-window")));
        reset(gateway);db.update("UPDATE agent_plan_scene SET source_ref='{}' WHERE ordinal_no=2");
        assertThrows(BusinessException.class,()->requotes().requote(1,conversation,old.id(),command(old,"source-change")));
        verifyNoInteractions(gateway,jobs);assertEquals(1,count("agent_generation_batch"));
    }

    // 【测什么】领域Task已存在但Agent未回填时，GET也不能显示重新核价入口；其他用户同名request不串。
    // 【怎么算红】删除eligible的同用户request账本检查，本人已有Task时资格仍true而断言变红。
    @Test void domainLedgerAlsoDisablesProjectedRecovery() throws Exception {
        var old=expired();String request=store.batchRequotes().requests(old).get(0);var s=store.owned(conversation,1,false);
        db.update("INSERT INTO video_task(user_id,request_id) VALUES(2,?)",request);
        assertTrue(store.batchRequotes().eligible(store,s,old));
        db.update("INSERT INTO video_task(user_id,request_id) VALUES(1,?)",request);
        assertFalse(store.batchRequotes().eligible(store,s,old));
        var snapshot=app.snapshot(1,conversation);
        assertFalse(snapshot.messages().stream().flatMap(m->java.util.stream.StreamSupport.stream(m.parts().spliterator(),false)).anyMatch(p->p.path("canRequote").asBoolean()));
        verifyNoInteractions(gateway,jobs);
    }

    // 【测什么】临时核价故障保留原请求重试语义，服务恢复后同key可核价，无LLM或自动批准。
    // 【怎么算红】把503全部转409，错误码断言变红；错误时写请求ledger则恢复后不创建新报价。
    @Test void temporaryQuoteFailureAllowsOriginalRequestRetry() throws Exception {
        var old=expired();var application=requotes();var command=command(old,"temporary");
        doThrow(new BusinessException(503,"temporary private details")).when(gateway).quote(anyString(),any());
        var failure=assertThrows(BusinessException.class,()->application.requote(1,conversation,old.id(),command));
        assertEquals(503,failure.getCode());assertEquals(1,count("agent_generation_batch"));
        assertNull(store.requestHash(store.owned(conversation,1,false),command.clientActionId()));
        doAnswer(a->new TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),"IMAGE",a.getArgument(1),quote.amount(),"CNY")).when(gateway).quote(anyString(),any());
        application.requote(1,conversation,old.id(),command);assertEquals(2,count("agent_generation_batch"));verifyNoInteractions(planner,jobs);
    }

    // 【测什么】60秒六幕续价后，新批准接入真实GenerationRuntime，完成屏障保留六份准确来源的视频作品。
    // 【怎么算红】复用旧approval/父调用或续价只改卡不接执行链，六任务/六成功幕/COMPLETED会失败。
    @Test void sixtySecondVideoRenewalCompletesAfterExplicitApproval() throws Exception {
        var v=expiredVideo(true);v.application().requote(1,conversation,v.batch().id(),command(v.batch(),"video-final"));
        when(gateway.submit(anyLong(),any(),anyString())).thenAnswer(a->"video-"+a.getArgument(2));
        when(gateway.read(anyLong(),anyString())).thenAnswer(a->new AgentGenerationGateway.TaskView(a.getArgument(1),"PROCESSING","VIDEO",null,false,false,null));
        verify(gateway,never()).submit(anyLong(),any(),anyString());accept();drainRequote();
        for(int ordinal:java.util.List.of(2,3,4,5,6,1)) {
            String approvalId=db.queryForObject("SELECT approval_id FROM agent_batch_item WHERE batch_id=? AND ordinal_no=?",String.class,grant(),ordinal);
            String task=approvals.get(approvalId).taskId();assertNotNull(task);
            when(gateway.read(1,task)).thenReturn(new AgentGenerationGateway.TaskView(task,"SUCCESS","VIDEO",null,false,false,null));
            jobs.enqueue(org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime.JOB,approvalId,approvalId);drainRequote();
        }
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals("EXPIRED",store.batches().get(v.batch().id()).status());assertEquals("SUCCEEDED",store.batches().get(grant()).status());
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='VIDEO' AND source_ref_json IS NOT NULL",Integer.class));
        verify(gateway,times(6)).submit(eq(1L),argThat(q->q.inputSnapshot().path("duration").asInt()==10),anyString());
        verifyNoInteractions(v.f().models);
    }
    void drainRequote() {
        int steps=0;while(db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class)>0){assertTrue(steps++<20);run();}
    }
}
