package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.skill.SkillOutputContractException;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentCreationCapabilitiesTest extends StoryboardVideoCapabilitiesTest {
    @Test void priorityOnlySelectsCompatibleModels() throws Exception {
        var properties=new org.example.seedancegenarate.config.AgentRuntimeProperties();
        properties.setVideoModelPriority(List.of("bad","z"));
        when(engine.models()).thenReturn(List.of(video("a",15,List.of()),video("bad",10,List.of(10)),video("z",15,List.of())));
        var registry=new org.example.seedancegenarate.engine.VideoEngineRegistry(List.of(engine));
        skill=new org.example.seedancegenarate.agent.skill.StoryboardGenerationSkill(llm,json,new org.example.seedancegenarate.agent.skill.StoryboardVideoCapabilities(generation,registry,access,json,properties));
        assertEquals("z",skill.execute(context(target(15)),node("{\"instruction\":\"分镜\"}")).data().path("videoCapabilities").path("model").asText());
    }
    ObjectNode target(int seconds) throws Exception {
        var p=(ObjectNode)plan();p.withObject("data").putObject("creationSpec").put("totalDurationSeconds",seconds).put("ratio","16:9");return p;
    }
    // 【测什么】明确30秒不被模型合法但总长只有15秒的结果冒充，属于有界输出修复而非服务不可用。
    // 【怎么算红】删除全板总和校验，单幕15秒会返回成功作品。
    @Test void knownTotalIsAnOutputContract() throws Exception {
        assertThrows(SkillOutputContractException.class,()->skill.execute(context(target(30)),node("{\"instruction\":\"分镜\"}")));
        verifyNoInteractions(submit);
    }
    // 【测什么】不可用时长组合在调用模型前拒绝，不通过生成更多错误分镜碰运气。
    // 【怎么算红】删除总量可达性筛选后13秒目标会调用LLM。
    @Test void unreachableTotalStopsBeforeLlm() throws Exception {
        when(engine.models()).thenReturn(List.of(video("only-ten",10,List.of(10))));
        assertThrows(VideoPreparationException.class,()->skill.execute(context(target(13)),node("{\"instruction\":\"分镜\"}")));
        verifyNoInteractions(llm,submit);
    }
    // 【测什么】即使模型第一画幅是1:1，计划16:9仍进入最终分镜能力，不被默认值替换。
    // 【怎么算红】未继承creationSpec.ratio会选模型首比例1:1。
    @Test void planRatioOverridesOnlyTheDefault() throws Exception {
        when(engine.models()).thenReturn(List.of(new org.example.seedancegenarate.engine.ModelSpec("local","square-first","视频",false,0,0,List.of("1:1","16:9"),5,15,List.of(),org.example.seedancegenarate.engine.OutputType.VIDEO)));
        var result=skill.execute(context(target(15)),node("{\"instruction\":\"分镜\"}"));
        assertEquals("16:9",result.data().path("videoCapabilities").path("ratio").asText());
        assertEquals(15,result.data().path("creationSpec").path("totalDurationSeconds").asInt());
    }
    // 【测什么】已确认16:9不允许下一步Planner替换成1:1，冲突在LLM调用前发生。
    // 【怎么算红】只从videoRequirements读取画幅会接受或晚于LLM发现冲突。
    @Test void conflictingRequestCannotReplaceTarget() throws Exception {
        assertThrows(org.example.seedancegenarate.exception.BusinessException.class,()->skill.execute(context(target(15)),node("{\"instruction\":\"分镜\",\"videoRequirements\":{\"ratio\":\"1:1\"}}")));
        verifyNoInteractions(llm,submit);
    }
}
