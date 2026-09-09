package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoryboardVideoCapabilitiesTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentModelGateway llm=mock(AgentModelGateway.class);
    final VideoEngine engine=mock(VideoEngine.class);
    final ModelAccessService access=mock(ModelAccessService.class);
    final VideoSubmitService submit=mock(VideoSubmitService.class);
    AgentGenerationGateway generation; StoryboardGenerationSkill skill;
    JsonNode node(String value)throws Exception{return json.readTree(value);}
    ModelSpec video(String id,int max,List<Integer> durations) {
        return new ModelSpec("local",id,id,false,0,0,List.of("16:9"),5,max,durations,OutputType.VIDEO);
    }
    @BeforeEach void setup() {
        when(engine.provider()).thenReturn("local");when(access.isOpen(anyString())).thenReturn(true);
        when(engine.models()).thenReturn(List.of(video("video",15,List.of())));
        var engines=new VideoEngineRegistry(List.of(engine));
        generation=new AgentGenerationGateway(engines,access,submit,mock(VideoTaskService.class),new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),json,mock(AgentDirectGenerationGateway.class));
        skill=new StoryboardGenerationSkill(llm,json,new StoryboardVideoCapabilities(generation,engines,access,json));
        response(15);
    }
    void response(Integer duration) {
        when(llm.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"幕\",\"visual\":\"猫走路\",\"narration\":\"回家\""+(duration==null?"":",\"duration\":"+duration)+"}]}");
    }
    AgentContext context(JsonNode plan) {
        return new AgentContext(1L,"session","turn","own","故事",null,List.of(),List.of(new AgentContext.ArtifactContext("script",1,"SCRIPT","脚本","猫回家")),0,List.of(),plan,new AgentContext.ArtifactRef("script",1,null));
    }
    JsonNode plan()throws Exception{return node("{\"confirmed\":true,\"currentStepId\":\"b\",\"data\":{\"steps\":[{\"id\":\"b\",\"kind\":\"STORYBOARD\"},{\"id\":\"v\",\"kind\":\"VIDEO\"}]}}");}
    SkillResult board()throws Exception{return skill.execute(context(plan()),node("{\"instruction\":\"分镜\"}"));}
    // 【测什么】视频计划分镜生成前获得真实闭区间能力，15秒保留并冻结模型和画幅。
    // 【怎么算红】删除prepare调用或把区间上限当成不包含15，能力/时长断言失败。
    @Test void fifteenSecondsUsesActualCapabilitiesBeforeModelCall()throws Exception {
        var result=board();assertEquals(15,result.data().path("scenes").get(0).path("duration").asInt());
        assertEquals("video",result.data().path("videoCapabilities").path("model").asText());
        var request=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(),eq("AGENT_STORYBOARD"),anyString(),request.capture());
        assertEquals(15,node(request.getValue()).path("videoCapabilities").path("durationMax").asInt());
        verifyNoInteractions(submit);
    }
    // 【测什么】纯文字分镜在全部视频模型关闭时仍能独立创作。
    // 【怎么算红】无VIDEO也强制准备能力时，execute会抛异常。
    @Test void textOnlyCreationDoesNotRequireVideoModels()throws Exception {
        when(access.isOpen(anyString())).thenReturn(false);
        var result=skill.execute(context(null),node("{\"instruction\":\"只写分镜\"}"));
        assertFalse(result.data().has("videoCapabilities"));verifyNoInteractions(access);
    }
    // 【测什么】明确15秒时，按现有model排序过滤不适配能力，不偷偷缩短时间。
    // 【怎么算红】未过滤离散5/8秒模型时会选a或报错。
    @Test void initialSelectionFiltersDurationBeforePickingModel()throws Exception {
        when(engine.models()).thenReturn(List.of(video("a",8,List.of(5,8)),video("b",15,List.of())));
        var result=skill.execute(context(plan()),node("{\"instruction\":\"15秒每幕\",\"videoRequirements\":{\"duration\":15}}"));
        assertEquals("b",result.data().path("videoCapabilities").path("model").asText());
    }
    // 【测什么】用户明确选定模型不支持15秒时，调用文本模型之前说明规格问题，不换模型。
    // 【怎么算红】忽略显式model或延后prepare会使异常/零LLM调用断言失败。
    @Test void explicitModelCannotSilentlySwitch()throws Exception {
        when(engine.models()).thenReturn(List.of(video("a",8,List.of(5,8)),video("b",15,List.of())));
        var error=assertThrows(VideoPreparationException.class,()->skill.execute(context(plan()),node("{\"instruction\":\"分镜\",\"videoRequirements\":{\"model\":\"a\",\"duration\":15}}")));
        assertEquals("VIDEO_DURATION_UNSUPPORTED",error.code());verifyNoInteractions(llm);
    }
    // 【测什么】角色参考只能选REFERENCE_IMAGE，不能将首帧或纯文本当作同一能力。
    // 【怎么算红】跳过参考模式校验时会选字典序更早的首帧模型a。
    @Test void characterReferenceFiltersFirstFrameModel()throws Exception {
        var first=new ModelSpec("local","a","首帧",true,1,1,List.of("16:9"),5,15,List.of()).withImageInputMode(ModelSpec.ImageInputMode.FIRST_FRAME);
        var reference=new ModelSpec("local","b","参考",true,1,1,List.of("16:9"),5,15,List.of()).withImageInputMode(ModelSpec.ImageInputMode.REFERENCE_IMAGE);
        when(engine.models()).thenReturn(List.of(first,reference));var p=plan();
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.path("data")).set("referenceImage",node("{\"artifactId\":\"image\",\"version\":2}"));
        var result=skill.execute(context(p),node("{\"instruction\":\"分镜\"}"));
        assertEquals("b",result.data().path("videoCapabilities").path("model").asText());
        assertEquals("REFERENCE_IMAGE",result.data().path("videoCapabilities").path("referenceMode").asText());
    }
    // 【测什么】没有参考图时不能选择必须图片输入的模型。
    // 【怎么算红】删除ModelSpec需求过滤后会选a，gateway.validate的继承路径无法挡住。
    @Test void absentImageCannotSelectImageRequiredModel()throws Exception {
        var image=new ModelSpec("local","a","图生",true,1,1,List.of("16:9"),5,15,List.of()).withImageInputMode(ModelSpec.ImageInputMode.FIRST_FRAME);
        when(engine.models()).thenReturn(List.of(image,video("b",15,List.of())));
        assertEquals("b",board().data().path("videoCapabilities").path("model").asText());
    }
    // 【测什么】分镜结果缺时长或超过模型范围会在落作品前给出规格错误，而不是502。
    // 【怎么算红】删validateScenes或将规格异常吞为格式502时断言失败。
    @Test void unsupportedAndMissingOutputDurationsFailEarly()throws Exception {
        response(16);assertEquals("VIDEO_DURATION_UNSUPPORTED",assertThrows(VideoPreparationException.class,this::board).code());
        response(null);assertEquals("VIDEO_SCENE_INVALID",assertThrows(VideoPreparationException.class,this::board).code());
    }
    // 【测什么】用户指定每幕15秒时，即使10秒在能力范围内也不能静默改成10秒。
    // 【怎么算红】删除明确duration与输出相等校验后execute成功。
    @Test void explicitDurationMustBePreserved()throws Exception {
        response(10);assertThrows(VideoPreparationException.class,()->skill.execute(context(plan()),node("{\"instruction\":\"分镜\",\"videoRequirements\":{\"duration\":15}}")));
    }
    AgentContext boardContext(SkillResult board,int version) {
        return new AgentContext(1L,"session","turn","own","故事",null,List.of(),
                List.of(new AgentContext.ArtifactContext("board",version,"STORYBOARD",board.title(),board.content(),board.data())),0,List.of(),null,new AgentContext.ArtifactRef("board",version,"s1"));
    }
    // 【测什么】视频报价沿精确分镜版本继承模型、画幅和本幕15秒，能力变化时仍现场复验。
    // 【怎么算红】删除metadata继承或报价实时校验会使缺参数失败或变更后的assertThrows失败。
    @Test void quotationInheritsFrozenSpecificationAndRevalidatesCurrentModel()throws Exception {
        var board=board();var c=boardContext(board,2);var video=new VideoGenerationSkill(generation);
        when(submit.estimate("local","video",15)).thenReturn(new VideoSubmitService.PriceEstimate("local","video",15,"VIDEO",BigDecimal.ONE,BigDecimal.ONE,"CNY"));
        var quote=video.quote(c,node("{\"prompt\":\"猫回家\"}"));
        assertEquals("video",quote.modelId());assertEquals(15,quote.inputSnapshot().path("duration").asInt());
        assertEquals("16:9",quote.inputSnapshot().path("ratio").asText());
        when(engine.models()).thenReturn(List.of(video("video",10,List.of())));
        assertEquals("VIDEO_DURATION_UNSUPPORTED",assertThrows(VideoPreparationException.class,()->video.quote(c,node("{\"prompt\":\"猫回家\"}"))).code());
        verify(submit,times(1)).estimate(anyString(),anyString(),anyInt());
    }
    // 【测什么】报价不得忽略绑定规格、偷偷换模型/画幅/时长或把不可访问版本替换为当前版本。
    // 【怎么算红】删除冲突检查或版本准确resolve会让至少一个错误输入进入estimate。
    @Test void quotationRejectsExplicitConflictsAndUnknownVersion()throws Exception {
        var c=boardContext(board(),2);var video=new VideoGenerationSkill(generation);
        for(String input:List.of("{\"prompt\":\"x\",\"model\":\"other\"}","{\"prompt\":\"x\",\"ratio\":\"1:1\"}",
                "{\"prompt\":\"x\",\"duration\":10}","{\"prompt\":\"x\",\"source\":{\"artifactId\":\"board\",\"version\":3,\"sceneId\":\"s1\"}}"))
            assertThrows(BusinessException.class,()->video.quote(c,node(input)));
        verifyNoInteractions(submit);
    }
    // 【测什么】只完成了前序VIDEO但后续只写分镜时，不错误强加视频能力。
    // 【怎么算红】忽略currentStepId扫描全部VIDEO时，关闭模型会阻止独立分镜。
    @Test void completedEarlierVideoDoesNotConstrainLaterTextStep()throws Exception {
        when(access.isOpen(anyString())).thenReturn(false);
        var p=node("{\"confirmed\":true,\"currentStepId\":\"b\",\"steps\":[{\"id\":\"v\",\"kind\":\"VIDEO\",\"status\":\"SUCCEEDED\"},{\"id\":\"b\",\"kind\":\"STORYBOARD\"}],\"data\":{}}");
        assertFalse(skill.execute(context(p),node("{\"instruction\":\"分镜\"}")).data().has("videoCapabilities"));
    }
    // 【测什么】局部修改保留分镜绑定规格和未选中幕，且只检查改动幕。
    // 【怎么算红】全板重建或metadata字段被白名单拒绝时，修改失败或丢失绑定。
    @Test void editingPreservesCapabilitiesAndUnchangedScenes()throws Exception {
        var board=board();var scenes=(com.fasterxml.jackson.databind.node.ArrayNode)board.data().path("scenes");
        scenes.add(scenes.get(0).deepCopy());((com.fasterxml.jackson.databind.node.ObjectNode)scenes.get(1)).put("sceneId","s2").put("duration",6);
        var other=scenes.get(1).deepCopy();
        when(llm.complete(any(),anyString(),anyString(),anyString())).thenReturn("{\"scene\":{\"title\":\"改幕\",\"visual\":\"猫进门\",\"narration\":\"到家\",\"duration\":15}}");
        var edited=skill.execute(boardContext(board,2),node("{\"instruction\":\"改第一幕\"}"));
        assertEquals(board.data().path("videoCapabilities"),edited.data().path("videoCapabilities"));
        assertEquals(other,edited.data().path("scenes").get(1));
    }
}
