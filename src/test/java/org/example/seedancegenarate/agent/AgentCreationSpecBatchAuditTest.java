package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.skill.VideoGenerationSkill;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent audit uses real quotation/preparation with local fake pricing; no media submission. */
class AgentCreationSpecBatchAuditTest extends AgentVideoPromptPreparationTest {
    AgentContext specified(int total, boolean firstSucceeded) {
        var original=context(15);
        var plan=(ObjectNode)original.plan().deepCopy();plan.put("confirmed",true);
        var spec=json.createObjectNode().put("totalDurationSeconds",total).put("ratio","16:9");
        plan.putObject("data").set("creationSpec",spec);
        var board=(ObjectNode)original.artifacts().get(0).data().deepCopy();board.set("creationSpec",spec.deepCopy());
        if(firstSucceeded)((ObjectNode)plan.path("steps").get(0).path("scenes").get(0)).put("status","SUCCEEDED");
        return new AgentContext(1L,"session","turn","llm","校园短片",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",1,"STORYBOARD","校园","完整分镜",board)),0,List.of(),plan,
                new AgentContext.ArtifactRef("board",1,firstSucceeded?"s2":"s1"));
    }
    // 【测什么】已成功首幕不重报价，剩余15秒仍按完整30秒目标验证；冻结准备保留16:9与整片规格。
    // 【怎么算红】按未完成幕求总和会拒绝合法15+15板；丢creationSpec注入或重做成功幕则断言变红。
    @Test void partialBatchKeepsWholeTargetAndSkipsSuccessfulScene() throws Exception {
        var context=specified(30,true);
        var quote=new VideoGenerationSkill(gateway).quote(context,json.createObjectNode().put("model",model).put("prompt","第二幕"));
        var batch=AgentBatchRuntime.prepare(gateway,context,quote);
        assertEquals(1,batch.items().size());assertEquals("c2",batch.items().get(0).sceneId());
        assertEquals(15,batch.items().get(0).quote().inputSnapshot().path("duration").asInt());
        assertEquals("16:9",batch.items().get(0).quote().inputSnapshot().path("ratio").asText());
        var prepared=preparation.preparePlan(context,quote,batch);
        assertEquals(30,prepared.scenes().get(0).request().path("creationSpec").path("totalDurationSeconds").asInt());
        assertEquals("16:9",prepared.scenes().get(0).request().path("creationSpec").path("ratio").asText());
        verifyNoInteractions(models);verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】全板总量错误不能因首幕已成功被忽略，剩余幕也不得报价或调用提示词模型。
    // 【怎么算红】删除VideoSkill全板validateTotal，25秒目标会照常拿30秒分镜报价。
    @Test void successfulSceneStillCountsWhenCheckingTargetBeforeQuote() throws Exception {
        var context=specified(25,true);
        assertThrows(BusinessException.class,()->new VideoGenerationSkill(gateway).quote(context,
                json.createObjectNode().put("model",model).put("prompt","第二幕")));
        verifyNoInteractions(models);verify(submit,never()).estimate(anyString(),anyString(),anyInt());
        verify(submit,never()).submitApproved(any(),any());
    }
    // 【测什么】准备hash稳定复用相同规格，但即使实际单幕quote相同，整片目标变化也使hash失效。
    // 【怎么算红】移除准备entry.creationSpec，25/30两个目标在相同quote/分镜/模型下会产生相同hash。
    @Test void checkpointBindingIncludesWholeTargetNotOnlySingleQuote() throws Exception {
        var a=specified(30,false);var quote=first(a);var batch=AgentBatchRuntime.prepare(gateway,a,quote);
        var one=preparation.preparePlan(a,quote,batch);
        assertEquals(one.bindingHash(),preparation.preparePlan(a,quote,batch).bindingHash());
        // Pure binding regression, not an accepted execution: production quote rejects this mismatched target.
        var b=specified(25,false);
        assertNotEquals(one.bindingHash(),preparation.preparePlan(b,quote,batch).bindingHash());
        verifyNoInteractions(models);verify(submit,never()).submitApproved(any(),any());
    }
}
