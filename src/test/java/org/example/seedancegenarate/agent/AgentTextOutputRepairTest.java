package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTextOutputRepairTest extends AgentRuntimeIntegrationTest {
    @BeforeEach void delayedJobs() {
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()));
    }
    void due(){db.update("UPDATE agent_model_recovery SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE status='WAITING_RETRY'");}
    SkillOutputContractException malformed(){return new SkillOutputContractException("DOCUMENT_SCHEMA $: 字段 title 必须为非空文本");}

    // 【测什么】格式失败重启后仍只修复原call，旧Job和成功重放不增加作品。
    // 【怎么算红】删掉原Job fencing或修复预算持久字段，调用次数/单作品/计数断言失败。
    @Test void repairsOriginalCallAfterRestartAndRejectsReplay() {
        when(skill.execute(any(),any())).thenThrow(malformed()).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));
        app.send(1,id,send("text-repair","写脚本"));run();var original=next();run();
        String call=store.read(original.getPayload()).path("callId").asText();
        assertEquals("WAITING_RETRY",store.call(call).status());
        store=new AgentStore(db,json);runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        runtime.execute(original,true);verify(skill,times(1)).execute(any(),any());
        due();var retry=next();run();runtime.execute(retry,true);
        assertEquals("SUCCEEDED",store.call(call).status());assertEquals(1,count("agent_artifact_version"));assertEquals(1,count("agent_skill_call"));
        verify(skill,times(2)).execute(any(),any());
        assertEquals(1,db.queryForObject("SELECT output_repairs FROM agent_model_recovery WHERE call_id=?",Integer.class,call));
    }

    // 【测什么】连续两次格式失败正常暂停，观察更新不触发唯一键冲突或第三次尝试。
    // 【怎么算红】观察直接重复INSERT或不限制一次repair时，第二次执行抛异常或不为SUSPENDED。
    @Test void repeatedInvalidOutputSuspendsWithoutDuplicateObservation() {
        when(skill.execute(any(),any())).thenThrow(malformed());
        app.send(1,id,send("twice","写脚本"));run();String call=store.read(next().getPayload()).path("callId").asText();run();due();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals("SUSPENDED",db.queryForObject("SELECT status FROM agent_skill_call",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_observation WHERE type='SKILL_OUTPUT_REPAIR'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        assertEquals(0,count("agent_artifact_version"));verify(skill,times(2)).execute(any(),any());
        assertEquals(1,count("agent_skill_call"));assertEquals("SUSPENDED",store.call(call).status());
        assertEquals(1,db.queryForObject("SELECT output_repairs FROM agent_model_recovery WHERE call_id=?",Integer.class,call));
        assertEquals(2,db.queryForObject("SELECT attempt_count FROM agent_model_recovery WHERE call_id=?",Integer.class,call));
    }

    // 【测什么】入队失败整笔回滚repair计数；重领保留机会，不把模型重复变成媒体执行。
    // 【怎么算红】reserve先行提交或不回滚时output_repairs不为0。
    @Test void failedEnqueueRollsBackRepairReservation() {
        when(skill.execute(any(),any())).thenThrow(malformed());app.send(1,id,send("rollback","脚本"));run();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("queue unavailable")).when(jobs).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        assertThrows(org.springframework.dao.DataAccessException.class,this::run);
        assertEquals(0,db.queryForObject("SELECT output_repairs FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_observation WHERE type='SKILL_OUTPUT_REPAIR'",Integer.class));
    }

    // 【测什么】引用权限业务失败不能获得输出修复次数。
    // 【怎么算红】将BusinessException统统当输出错误则会入队并进入WAITING_RETRY。
    @Test void inputAndPermissionFailuresNeverRepair() {
        when(skill.execute(any(),any())).thenThrow(BusinessException.forbidden("引用不可访问"));
        app.send(1,id,send("forbidden","脚本"));run();run();
        verify(jobs,never()).enqueueDelayed(anyString(),anyString(),anyString(),anyLong());
        assertEquals(0,db.queryForObject("SELECT output_repairs FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
        verify(skill,times(1)).execute(any(),any());
    }

    // 【测什么】输出修复中超时可用剩余尝试，但不刷新一次格式修复额度。
    // 【怎么算红】timeout清空output_repairs或忽略总预算则第三次格式失败后仍会继续入队。
    @Test void timeoutDuringRepairDoesNotResetOutputAllowance() {
        when(skill.execute(any(),any())).thenThrow(malformed())
                .thenThrow(org.example.seedancegenarate.service.llm.LlmChannelException.readTimeout(new java.net.http.HttpTimeoutException("timeout")))
                .thenThrow(malformed());
        app.send(1,id,send("mixed","脚本"));run();run();due();run();due();run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals(1,db.queryForObject("SELECT output_repairs FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
        assertEquals(3,db.queryForObject("SELECT attempt_count FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        verify(skill,times(3)).execute(any(),any());
    }
}
