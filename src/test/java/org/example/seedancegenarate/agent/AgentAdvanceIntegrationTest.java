package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Independent acceptance through real SQL/Runtime. No model, GPU, wallet or media provider is called. */
class AgentAdvanceIntegrationTest extends AgentPersistentPlanTest {
    // 【测什么】最后必要文字步骤落真实作品即确定性交付，不再用尾部Planner决定成功；旧Job无二次副作用。
    // 【怎么算红】恢复finishSkill无条件continueTurn时，Turn仍QUEUED且多一个Job；移除fence则重放增加作品。
    @Test void finalArtifactCompletesWithoutAnUnreliableClosingPlanner() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation", "1", "script", json.createObjectNode(), "SCRIPT"));
        adopt(List.of(Map.of("id", "script", "kind", "SCRIPT", "title", "脚本")));
        when(planner.decide(any())).thenReturn(callScript());
        when(skill.execute(any(), any())).thenReturn(new SkillResult("SCRIPT", "校园的一天", "30秒校园宣传片完整脚本", null));
        run();
        var executing = next();
        doThrow(new AssertionError("completed plan must never invoke closing Planner")).when(planner).decide(any());
        runtime.execute(executing, true);
        assertEquals("COMPLETED", app.snapshot(1, id).turn().status());
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(0, pendingJobs());
        var result = app.snapshot(1, id).artifacts().stream().filter(a -> "SCRIPT".equals(a.type())).findFirst().orElseThrow();
        assertEquals("30秒校园宣传片完整脚本", result.content());
        var ref = json.valueToTree(Map.of("artifactId", result.id(), "version", result.version()));
        assertEquals(ref, store.read(db.queryForObject("SELECT result_ref FROM agent_plan_step", String.class)));
        long messages = count("conversation_message");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE content LIKE '计划步骤已完成，已生成：%'", Integer.class));
        runtime.execute(executing, true);
        assertEquals(messages, count("conversation_message"));
        assertEquals(2, count("agent_artifact_version"));
        verify(skill, times(1)).execute(any(), any());
        verify(planner, times(1)).decide(any());
        doReturn(ask()).when(planner).decide(any());
        app.send(1, id, send("followup", "你好，接下来创作什么？"));
        run();
        assertEquals("WAITING_USER", app.snapshot(1, id).turn().status(), "new user message must not be swallowed by completed plan");
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE content LIKE '计划步骤已完成，已生成：%'", Integer.class));
        verify(planner, times(2)).decide(any());
    }

    // 【测什么】前序脚本成功后下游业务异常只失败执行切片，计划暂停并保留成功作品及绑定。
    // 【怎么算红】AgentStore.status继续把FAILED复制到Plan，或失败清空result_ref，这条变红。
    @Test void downstreamFailurePreservesPlanAndSuccessfulResult() {
        startScript();
        when(skill.execute(any(), any())).thenReturn(new SkillResult("SCRIPT", "脚本", "已经完成的脚本", null));
        run();
        String ref = db.queryForObject("SELECT result_ref FROM agent_plan_step WHERE step_key='script'", String.class);
        var source = app.snapshot(1, id).artifacts().stream().filter(a -> "SCRIPT".equals(a.type())).findFirst().orElseThrow();
        var board = mock(CreativeSkill.class);
        when(board.descriptor()).thenReturn(new SkillDescriptor("storyboard-generation", "1", "board", json.createObjectNode(), "STORYBOARD"));
        when(board.execute(any(), any())).thenThrow(new BusinessException(400, "unsupported business input"));
        var input = json.createObjectNode().put("instruction", "分镜");
        input.set("source", json.valueToTree(new AgentContext.ArtifactRef(source.id(), source.version(), null)));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL", null, null, List.of(), "storyboard-generation", input));
        runtime = new AgentRuntime(store, jobs, tx, planner, new SkillRegistry(List.of(skill, board)), json,
                mock(AgentGenerationRuntime.class), diagnostics, models);
        run(); run();
        assertEquals("FAILED", app.snapshot(1, id).turn().status());
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM agent_plan_step WHERE step_key='script'", String.class));
        assertEquals(ref, db.queryForObject("SELECT result_ref FROM agent_plan_step WHERE step_key='script'", String.class));
        assertEquals("已经完成的脚本", app.snapshot(1, id).artifacts().stream()
                .filter(a -> source.id().equals(a.id()) && source.version() == a.version()).findFirst().orElseThrow().content());
        assertEquals(2, count("agent_artifact_version"));
        assertEquals(0, pendingJobs());
        verify(skill, times(1)).execute(any(), any());
        verify(board, times(1)).execute(any(), any());
    }

    // 【测什么】DB步骤被误记成功但结果缺失、引用未知身份/版本或错误类型，不能仅数status就宣告交付。
    // 【怎么算红】completePlanIfReady仅COUNT未完成步骤而不检查准确结果身份/版本/类型时，会得到SUCCEEDED。
    @ParameterizedTest @ValueSource(strings = {"missing", "unknown-artifact", "wrong-version", "wrong-type"})
    void successfulStepFlagsWithoutArtifactAreNotDelivery(String corruption) {
        adopt();
        var draft = app.snapshot(1, id).artifacts().stream().filter(a -> "PLAN".equals(a.type())).findFirst().orElseThrow();
        String result = "missing".equals(corruption) ? null : json.createObjectNode()
                .put("artifactId", "unknown-artifact".equals(corruption) ? "not-existing" : draft.id())
                .put("version", "wrong-version".equals(corruption) ? 999 : 1).toString();
        db.update("UPDATE agent_plan_step SET status='SUCCEEDED',result_ref=?", result);
        assertEquals(1, count("agent_plan"));
        when(planner.decide(any())).thenReturn(new AgentDecision("COMPLETE", "完成", null, List.of(), null, null));
        run();
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertEquals("SUSPENDED", app.snapshot(1, id).turn().status());
        assertTrue(app.snapshot(1, id).turn().error().contains("PLAN_OUTPUT_INCOMPLETE"));
        verifyNoInteractions(planner);
        assertEquals(1, count("agent_artifact_version"), "only plan draft exists, no deliverable");
    }

    // 【测什么】已成功前序步骤后预算Yield续接同Plan，旧切片重放和旧epoch均不能多调模型或作品。
    // 【怎么算红】移除current epoch/activeTurn fence或Yield绑定迁移，旧Job将执行Planner或改Plan归属。
    @Test void yieldAfterAnArtifactKeepsProgressAndFencesOldSlice() {
        startScript();
        when(skill.execute(any(), any())).thenReturn(new SkillResult("SCRIPT", "脚本", "已完成", null));
        run();
        var before = store.turn(app.snapshot(1, id).turn().id());
        String ref = db.queryForObject("SELECT result_ref FROM agent_plan_step WHERE step_key='script'", String.class);
        db.update("UPDATE agent_turn SET step_no=16 WHERE id=?", before.id());
        var old = next();
        old.setPayload(store.write(new AgentRuntime.Payload(before.id(), before.epoch(), 16, null)));
        runtime.execute(old, false);
        var child = app.snapshot(1, id).turn();
        assertNotEquals(before.id(), child.id());
        assertEquals("YIELDED", store.turn(before.id()).status());
        assertEquals(child.id(), db.queryForObject("SELECT turn_id FROM agent_plan", String.class));
        runtime.execute(old, false);
        var currentJob = next();
        db.update("UPDATE agent_turn SET epoch=epoch+1 WHERE id=?", child.id());
        runtime.execute(currentJob, false);
        assertEquals(ref, db.queryForObject("SELECT result_ref FROM agent_plan_step WHERE step_key='script'", String.class));
        assertEquals(2, count("agent_artifact_version"));
        verify(planner, times(1)).decide(any());
        verify(skill, times(1)).execute(any(), any());
    }

    // 【测什么】准确作品ID与类型存在，但Step生产者关联被篡改成别的Call，也不能通过交付验收。
    // 【怎么算红】delivered不检查expectedCall与source_call_id一致性，这条会误完成并发出交付摘要。
    @Test void exactArtifactWithWrongProducerCannotBeDelivered() {
        startScript();
        when(skill.execute(any(), any())).thenReturn(new SkillResult("SCRIPT", "脚本", "已完成正文", null));
        run();
        db.update("UPDATE agent_plan_step SET status='SKIPPED' WHERE step_key='board'");
        String otherCall = db.queryForObject("SELECT id FROM agent_skill_call WHERE skill_id='plan-generation'", String.class);
        db.update("UPDATE agent_plan_step SET skill_call_id=? WHERE step_key='script'", otherCall);
        clearInvocations(planner);
        run();
        assertEquals("SUSPENDED", app.snapshot(1, id).turn().status());
        assertEquals("SUSPENDED", db.queryForObject("SELECT status FROM agent_plan", String.class));
        assertTrue(app.snapshot(1, id).turn().error().contains("PLAN_OUTPUT_INCOMPLETE"));
        verifyNoInteractions(planner);
        assertEquals(2, count("agent_artifact_version"));
    }

    private int pendingJobs() { return db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE", Integer.class); }
}
