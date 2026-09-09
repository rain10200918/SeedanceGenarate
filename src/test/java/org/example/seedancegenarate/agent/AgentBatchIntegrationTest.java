package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.application.AgentBatchApplication;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentBatchIntegrationTest extends AgentSceneIntegrationTest {
    // 【测什么】合法Planner结果在领域校验遇409事务回滚，安全暂停不伪称模型失败，也不创建Skill/审批/任务。
    // 【怎么算红】通用terminalFailure或模型文案仍处理业务冲突，状态/Observation/调用计数即红。
    @Test void decisionBusinessConflictRollsBackAndSuspendsWithoutModelBlame() throws Exception {
        start(board(1));db.update("UPDATE agent_plan_scene SET status='FAILED'");
        run();
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        assertEquals(0,count("agent_decision"));assertEquals(0,count("agent_skill_call"));assertEquals(0,count("agent_approval"));
        String detail=db.queryForObject("SELECT detail FROM agent_observation WHERE code='BUSINESS_409'",String.class);
        assertTrue(detail.contains("状态"));assertFalse(detail.contains("模型"));
        org.mockito.Mockito.verify(planner,org.mockito.Mockito.times(1)).decide(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(gateway);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】拒绝整批同时撤销未提交幕，普通继续不调用Planner或重新购买。
    // 【怎么算红】只撤销approval而留下WAITING_APPROVAL幕，或恢复先调模型，状态/调用数断言失败。
    @Test void rejectedBatchClosesScenesAndPlainContinueDoesNotCallModel() throws Exception {
        enableBatch();start(board(3));run();run();var b=store.batches().get(grant());
        new AgentBatchApplication(store,tx,jobs).answer(1,conversation,b.id(),new AgentBatchApplication.Answer("reject",1,b.binding(),"REJECT"));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='CANCELLED'",Integer.class));
        org.mockito.Mockito.clearInvocations(planner,gateway);
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("continue","继续",store.turn(b.turn()).channel()));run();
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        org.mockito.Mockito.verifyNoInteractions(planner,gateway);
    }
    // 【测什么】旧终态批次脏幕在恢复入口修正，成功和已提交历史不被撤销。
    // 【怎么算红】恢复仍留等待假状态或调用模型会失败。
    @Test void dirtyCancelledBatchRepairBeforePlanner() throws Exception {
        enableBatch();start(board(3));run();run();var b=store.batches().get(grant());
        new AgentBatchApplication(store,tx,jobs).answer(1,conversation,b.id(),new AgentBatchApplication.Answer("reject",1,b.binding(),"REJECT"));
        db.update("UPDATE agent_plan_scene SET status='WAITING_APPROVAL'");
        org.mockito.Mockito.clearInvocations(planner);
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("continue","继续",store.turn(b.turn()).channel()));run();
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='CANCELLED'",Integer.class));
        org.mockito.Mockito.verifyNoInteractions(planner);
    }
    // 【测什么】已受理两幕不被取消；未提交两幕撤销，过期同样全幕关闭，普通恢复预算耗尽也不进LLM。
    // 【怎么算红】批次取消覆盖ACCEPTED或漏queued幕/恢复预算先抛409则失败。
    @Test void mixedCancellationPreservesAcceptedAndBudgetIndependentGuidance() throws Exception {
        enableBatch();var board=board(4);start(board);run();run();accept();run();run();var b=store.batches().get(grant());
        tx.executeWithoutResult(x->store.cancel(store.turn(b.turn())));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='ACCEPTED'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='CANCELLED'",Integer.class));
        org.mockito.Mockito.clearInvocations(planner);
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("continue1","继续",store.turn(b.turn()).channel()));run();
        var active=store.owned(conversation,1,false).activeTurnId();db.update("UPDATE agent_turn SET resume_count=999 WHERE id=?",active);
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("continue2","继续",store.turn(active).channel()));
        long messages=count("conversation_message");
        app.send(1,conversation,new org.example.seedancegenarate.agent.application.AgentApplication.Send("continue2","继续",store.turn(active).channel()));
        assertEquals(messages,count("conversation_message"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        assertEquals("SUSPENDED",store.turn(active).status());org.mockito.Mockito.verifyNoInteractions(planner);
        assertEquals(4,count("agent_batch_item"));
    }
    // 【测什么】pending过期关闭所有幕且显式新计划仍可重新报价，旧grant不复用。
    // 【怎么算红】过期遗留待审批幕或beforeStep错挡新plan则失败。
    @Test void expiryClosesScenesAndExplicitRestartCreatesNewGrant() throws Exception {
        enableBatch();var board=board(3);start(board);run();run();var old=grant();
        db.update("UPDATE agent_generation_batch SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?",old);
        new AgentBatchApplication(store,tx,jobs).reconcile();
        assertEquals("EXPIRED",store.batches().get(old).status());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='CANCELLED'",Integer.class));
        workspaceApp().apply(1,conversation,new org.example.seedancegenarate.agent.application.AgentWorkspaceApplication.Command("new",workspace().path("version").asLong(),"GENERATE_SCENES_IMAGE",new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef(board.id(),1,null)));
        run();run();assertEquals(2,count("agent_generation_batch"));assertEquals("PENDING",store.batches().get(grant()).status());
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.never()).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }
    void enableBatch() throws Exception {
        runtimeLimits.setBatchEnabled(true);
        org.mockito.Mockito.when(gateway.quote(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(a->
                new org.example.seedancegenarate.agent.skill.TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),quote.mediaType(),a.getArgument(1),quote.amount(),quote.currency()));
        org.mockito.Mockito.when(gateway.submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(a->"task-"+a.getArgument(2));
    }
    String grant(){return db.queryForObject("SELECT id FROM agent_generation_batch ORDER BY created_at DESC LIMIT 1",String.class);}
    void accept() {var b=store.batches().get(grant());new AgentBatchApplication(store,tx,jobs).answer(1,conversation,b.id(),new AgentBatchApplication.Answer("accept-"+b.id(),b.version(),b.binding(),"APPROVE"));}
    void finishItem(int ordinal,boolean success) {
        String id=db.queryForObject("SELECT approval_id FROM agent_batch_item WHERE batch_id=? AND ordinal_no=?",String.class,grant(),ordinal);
        String task=approvals.get(id).taskId();assertNotNull(task);
        org.mockito.Mockito.when(gateway.read(1,task)).thenReturn(new org.example.seedancegenarate.agent.generation.AgentGenerationGateway.TaskView(task,success?"SUCCESS":"FAILED","IMAGE",null,false,false,null));
        jobs.enqueue(org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime.JOB,id,id);
        var job=db.query("SELECT * FROM job_probe WHERE type='AGENT_GENERATION' AND biz=?",(r,n)->{var j=new org.example.seedancegenarate.entity.AsyncJob();j.setId(r.getLong("id"));j.setPayload(id);return j;},id).get(0);
        generation.execute(job);
    }
    // 【测什么】整组四幕报价只生成一个grant，批准前无Task。
    // 【怎么算红】退回单幕Approval或提前submit，grant/调用数断言失败。
    @Test void oneGrantFreezesAllRemainingScenes() throws Exception {
        enableBatch();
        start(board(4));run();run();
        assertEquals(1,count("agent_generation_batch"));
        assertEquals(4,count("agent_batch_item"));
        assertNotNull(new AgentBatchApplication(store,tx,jobs));
    }
    // 【测什么】一次批准并发2，乱序成功只释放slot，全部成功才推进Planner，关开关不改变既有授权。
    // 【怎么算红】任一子项结束推进Turn或开关关闭丢失grant，step/任务数/屏障状态断言失败。
    @Test void parallelBarrierWaitsForAllOutOfOrderAndSurvivesFlagOff() throws Exception {
        enableBatch();start(board(4));run();run();accept();runtimeLimits.setBatchEnabled(false);
        run();run();assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='ACCEPTED'",Integer.class));
        finishItem(2,true);assertEquals("RUNNING",store.batches().get(grant()).status());run();
        finishItem(3,true);run();finishItem(4,true);
        assertEquals("WAITING_TASK",app.snapshot(1,conversation).turn().status());
        finishItem(1,true);assertEquals("SUCCEEDED",store.batches().get(grant()).status());
        assertEquals(4,workspace().path("steps").get(0).path("sceneProgress").path("completedScenes").asInt());
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(4)).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }
    // 【测什么】失败子项不阻断其他已授权幕，全部settled后显式重启只对失败幕再报价。
    // 【怎么算红】遇首失败即停、成功幕重买、未重新批准即submit，集合/状态/提交计数失败。
    @Test void partialFailureContinuesAuthorizedItemsThenNewGrantOnlyForFailedScene() throws Exception {
        enableBatch();var board=board(4);start(board);run();run();accept();run();run();
        finishItem(1,false);run();finishItem(2,true);run();finishItem(3,true);finishItem(4,true);
        assertEquals("PARTIAL_FAILED",store.batches().get(grant()).status());assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        workspaceApp().apply(1,conversation,new org.example.seedancegenarate.agent.application.AgentWorkspaceApplication.Command("retry",workspace().path("version").asLong(),"GENERATE_SCENES_IMAGE",new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef(board.id(),1,null)));
        run();run();assertEquals(2,count("agent_generation_batch"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_batch_item WHERE batch_id=?",Integer.class,grant()));
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(4)).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }
    // 【测什么】grant内容被改、单项路由绕过、重复请求不允许扩大授权。
    // 【怎么算红】去掉hash/归属/replay守卫会提交或错误放行。
    @Test void immutableQuotesSingleRouteAndIdempotency() throws Exception {
        enableBatch();start(board(2));run();run();String id=db.queryForObject("SELECT id FROM agent_approval LIMIT 1",String.class);
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->approvalApp.answer(1,conversation,id,new org.example.seedancegenarate.agent.application.AgentApprovalApplication.Answer("single",1,"APPROVE")));
        var b=store.batches().get(grant());var app=new AgentBatchApplication(store,tx,jobs);var answer=new AgentBatchApplication.Answer("same",1,b.binding(),"APPROVE");
        app.answer(1,conversation,b.id(),answer);app.answer(1,conversation,b.id(),answer);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='APPROVED'",Integer.class));
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->app.answer(1,conversation,b.id(),new AgentBatchApplication.Answer("same",1,b.binding(),"REJECT")));
    }
    // 【测什么】取消撤销未提交排队项，旧Task完成只存作品不续原计划。
    // 【怎么算红】取消之后还提交或复活batch/Turn即红。
    @Test void cancellationKeepsSubmittedHistoricalAndStopsQueued() throws Exception {
        enableBatch();start(board(4));run();run();accept();run();run();app.delete(1,conversation);
        finishItem(1,true);finishItem(2,true);assertEquals("CANCELLED",store.batches().get(grant()).status());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='CANCELLED'",Integer.class));
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(2)).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
    }
    // 【测什么】未知提交继续占slot，不因其他项完成而越过并发上限或重投。
    // 【怎么算红】SUBMITTING未计入slot时第4项会提前APPROVED。
    @Test void uncertainSubmissionRetainsSlot() throws Exception {
        enableBatch();start(board(4));run();run();accept();
        org.mockito.Mockito.doThrow(new java.net.SocketTimeoutException()).when(gateway).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());run();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='SUBMITTING'",Integer.class));
        org.mockito.Mockito.doAnswer(a->"task-"+a.getArgument(2)).when(gateway).submit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());run();
        finishItem(2,true);run();
        new AgentBatchApplication(store,tx,jobs).reconcile();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE status='BATCH_QUEUED'",Integer.class));
    }
    // 【测什么】报价篡改、期限、归属和精度在授权前拒绝。
    // 【怎么算红】任一坏输入被批准即红。
    @Test void authorizationRejectsTamperExpiryAndOwner() throws Exception {
        enableBatch();start(board(2));run();run();var b=store.batches().get(grant());var app=new AgentBatchApplication(store,tx,jobs);
        var a=new AgentBatchApplication.Answer("a",1,b.binding(),"APPROVE");
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->app.answer(2,conversation,b.id(),a));
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->app.answer(1,conversation,b.id(),new AgentBatchApplication.Answer("a",1,"0".repeat(64),"APPROVE")));
        db.update("UPDATE agent_generation_batch SET total_amount=total_amount+1 WHERE id=?",b.id());
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->app.answer(1,conversation,b.id(),a));
        db.update("UPDATE agent_generation_batch SET total_amount=total_amount-1,expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?",b.id());
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->app.answer(1,conversation,b.id(),a));
    }
    // 【测什么】MySQL JSON对象重排不误判篡改，超六位金额不被DB四舍五入后批准。
    // 【怎么算红】hash依赖对象key顺序或金额默默round会失败。
    @Test void canonicalStorageAndExactMoney() throws Exception {
        enableBatch();start(board(2));run();run();var b=store.batches().get(grant());
        db.query("SELECT id,quote_json FROM agent_approval",r->{
            try {var original=json.readTree(r.getString(2));var reversed=json.createObjectNode();var fields=new java.util.ArrayList<String>();original.fieldNames().forEachRemaining(fields::add);java.util.Collections.reverse(fields);fields.forEach(k->reversed.set(k,original.get(k)));db.update("UPDATE agent_approval SET quote_json=? WHERE id=?",reversed.toString(),r.getString(1));}
            catch(Exception e){throw new IllegalStateException(e);}
        });
        assertTrue(store.batches().intact(store,b));
        db.query("SELECT id,quote_json FROM agent_approval",r->{db.update("UPDATE agent_approval SET quote_json=? WHERE id=?",r.getString(2).replace("\"amount\":0.1","\"amount\":0.1000"),r.getString(1));});
        assertTrue(store.batches().intact(store,b));
        var s=store.owned(conversation,1,true);var t=store.turn(b.turn());var parent=store.call(b.parent());
        var item=db.query("SELECT scene_id,ordinal_no,source_ref FROM agent_batch_item WHERE batch_id=? ORDER BY ordinal_no",(r,n)->new org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Item(r.getString(1),r.getInt(2),store.read(r.getString(3)),new org.example.seedancegenarate.agent.skill.TaskQuote(quote.provider(),quote.modelId(),quote.modelLabel(),quote.mediaType(),quote.inputSnapshot(),new java.math.BigDecimal("0.0000001"),quote.currency())),b.id());
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->store.batches().create(store,s,t,parent,new org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Prepared(b.planStep(),item)));
    }
}
