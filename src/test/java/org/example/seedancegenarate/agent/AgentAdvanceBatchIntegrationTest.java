package org.example.seedancegenarate.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Existing real approval/barrier SQL fixture, fake media domain; not a merge or provider-quality test. */
class AgentAdvanceBatchIntegrationTest extends AgentBatchIntegrationTest {
    // 【测什么】误排新Decision作业时，持久化未完成Task事实优先，恢复等待而不再调用模型。
    // 【怎么算红】移除begin的outstanding门槛，合法新Job会调用Planner并制造重复决策。
    @Test void incorrectlyQueuedDecisionReturnsToWaitingForExistingTasks() throws Exception {
        enableBatch(); start(board(4)); run(); run(); accept(); run(); run();
        clearInvocations(planner);
        db.update("UPDATE agent_turn SET status='QUEUED',step_no=step_no+1 WHERE id=?", app.snapshot(1, conversation).turn().id());
        var queued = store.turn(app.snapshot(1, conversation).turn().id());
        jobs.enqueue(org.example.seedancegenarate.agent.runtime.AgentRuntime.STEP_JOB,
                queued.id() + ":" + queued.epoch() + ":" + queued.step(),
                store.write(new org.example.seedancegenarate.agent.runtime.AgentRuntime.Payload(queued.id(), queued.epoch(), queued.step(), null)));
        run();
        assertEquals("WAITING_TASK", app.snapshot(1, conversation).turn().status());
        verifyNoInteractions(planner);
        verify(gateway, times(2)).submit(anyLong(), any(), anyString());
    }

    // 【测什么】单项批准到期仍可结束Turn，但不再隐式失败整个已采用计划。
    // 【怎么算红】expire使用纯status漏Plan暂停或将FAILED传播Plan，计划状态断言失败。
    @Test void expiredSingleApprovalFailsSliceButOnlySuspendsPlan() throws Exception {
        runtimeLimits.setBatchEnabled(false); start(board(1)); run(); run();
        String approval = db.queryForObject("SELECT id FROM agent_approval", String.class);
        db.update("UPDATE agent_approval SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?", approval);
        clearInvocations(planner, gateway);
        approvalApp.expire(); approvalApp.expire();
        assertEquals("EXPIRED", approvals.get(approval).status());
        assertEquals("FAILED", app.snapshot(1, conversation).turn().status());
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        verifyNoInteractions(planner, gateway);
    }

    // 【测什么】待批整组授权过期后显式暂停Plan，不能把到期看作完成或继续提交；reconcile可重复。
    // 【怎么算红】expiry仅改Turn而漏Plan暂停，或advance忽略过期授权，会状态错或调用Planner/submit。
    @Test void expiredApprovalSuspendsPlanWithoutAdvancingOrSubmitting() throws Exception {
        enableBatch(); start(board(4)); run(); run();
        var batch = store.batches().get(grant());
        db.update("UPDATE agent_generation_batch SET expires_at=TIMESTAMPADD(MINUTE,-1,NOW()) WHERE id=?", batch.id());
        clearInvocations(planner, gateway);
        var batchApp = new org.example.seedancegenarate.agent.application.AgentBatchApplication(store, tx, jobs);
        batchApp.reconcile(); batchApp.reconcile();
        assertEquals("EXPIRED", store.batches().get(batch.id()).status());
        assertEquals("SUSPENDED", app.snapshot(1, conversation).turn().status());
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='CANCELLED'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        verifyNoInteractions(planner, gateway);
    }

    // 【测什么】一次审批四幕乱序返回，最后真实作品收口即交付；等待和重复回调不多调模型、提交或发总结。
    // 【怎么算红】batch结束仍入Planner尾Job、首个结果就完成、或重复结果绕过fence，状态/计数断言即红。
    @Test void batchDeliveryNeedsEveryArtifactButNoClosingModel() throws Exception {
        enableBatch(); start(board(4));
        var decisionJob = next(); run(); run();
        assertEquals("WAITING_APPROVAL", app.snapshot(1, conversation).turn().status());
        assertEquals(1, count("agent_plan"));
        verify(gateway, never()).submit(anyLong(), any(), anyString());
        doThrow(new AssertionError("no tail Planner after media barrier")).when(planner).decide(any());
        runtime.execute(decisionJob, false);
        assertEquals("WAITING_APPROVAL", app.snapshot(1, conversation).turn().status());
        var batch = store.batches().get(grant());
        var approvalApp = new org.example.seedancegenarate.agent.application.AgentBatchApplication(store, tx, jobs);
        var answer = new org.example.seedancegenarate.agent.application.AgentBatchApplication.Answer("same-approval", batch.version(), batch.binding(), "APPROVE");
        approvalApp.answer(1, conversation, batch.id(), answer);
        approvalApp.answer(1, conversation, batch.id(), answer);
        run(); run();
        assertEquals("WAITING_TASK", app.snapshot(1, conversation).turn().status());
        finishItem(2, true); run(); finishItem(3, true); run(); finishItem(4, true);
        assertEquals("WAITING_TASK", app.snapshot(1, conversation).turn().status());
        assertNotEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        finishItem(1, true);
        assertEquals("COMPLETED", app.snapshot(1, conversation).turn().status());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE status='SUCCEEDED' AND result_ref IS NOT NULL", Integer.class));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE", Integer.class));
        long messages = count("conversation_message");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE content LIKE '计划步骤已完成，已生成：%'", Integer.class));
        finishItem(1, true); finishItem(4, true);
        assertEquals(messages, count("conversation_message"));
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        verify(planner, times(1)).decide(any());
        verify(gateway, times(4)).submit(anyLong(), any(), anyString());
    }

    // 【测什么】四幕中永久失败一幕，其他已批准项完成并保留，但不能因批次已终态就交付计划。
    // 【怎么算红】advance把PARTIAL_FAILED当成功或丢成功Artifact，Plan状态/成功数/无隐式重投断言变红。
    @Test void partialBatchCannotBecomeSuccessfulDelivery() throws Exception {
        enableBatch(); start(board(4)); run(); run(); accept(); run(); run();
        finishItem(1, false); run(); finishItem(2, true); run(); finishItem(3, true); finishItem(4, true);
        assertEquals("PARTIAL_FAILED", store.batches().get(grant()).status());
        assertEquals("SUSPENDED", app.snapshot(1, conversation).turn().status());
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        long messages = count("conversation_message");
        finishItem(1, false); finishItem(2, true);
        assertEquals(messages, count("conversation_message"));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE type='IMAGE'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE", Integer.class));
        verify(gateway, times(4)).submit(anyLong(), any(), anyString());
        verify(planner, times(1)).decide(any());
    }
}
