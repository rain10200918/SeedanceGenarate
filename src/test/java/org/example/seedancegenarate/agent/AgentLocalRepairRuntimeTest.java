package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.AgentDecision;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentLocalRepairRuntimeTest extends AgentRuntimeIntegrationTest {
    // 【测什么】模型准备失败发生在真实Call中，保存可信失败而不是丢失到Planner验证阶段。
    // 【怎么算红】恢复finishDecision中的VIDEO能力预检，将没有可归属的Call。
    @Test void unavailableModelHasRealPreparationCall() {
        var gateway=mock(AgentGenerationGateway.class);
        doThrow(new VideoPreparationException(VideoPreparationException.Reason.MODEL)).when(gateway).validate(eq("VIDEO"),any());
        when(gateway.quoteVideo(any(),any())).thenThrow(new VideoPreparationException(VideoPreparationException.Reason.MODEL));
        var video=new VideoGenerationSkill(gateway);
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(video)),json,
                mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL","准备",null,List.of(),"video-generation",
                json.createObjectNode().put("model","closed").put("prompt","猫")));
        app.send(1,id,send("repair-start","制作视频"));run();
        assertEquals(1,count("agent_skill_call"));run();
        assertEquals("SUSPENDED",app.snapshot(1,id).turn().status());
        assertEquals("VIDEO_MODEL_UNAVAILABLE",app.snapshot(1,id).state().actionableError().path("code").asText());
        assertEquals(0,count("agent_approval"));
    }
}
