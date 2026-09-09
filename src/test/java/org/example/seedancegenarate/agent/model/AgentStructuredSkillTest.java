package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentStructuredSkillTest {
    final ObjectMapper json = new ObjectMapper();
    final AgentModelGateway gateway = mock(AgentModelGateway.class);
    JsonNode node(String value) throws Exception { return json.readTree(value); }
    AgentContext context(AgentContext.ArtifactContext artifact, AgentContext.ArtifactRef selection) {
        return new AgentContext(1L,"s","t","own","宣传片",null,List.of(),
                artifact == null ? List.of() : List.of(artifact),0,List.of(),null,selection);
    }
    void response(String value) { when(gateway.complete(any(),anyString(),anyString(),anyString())).thenReturn(value); }

    // 【测什么】带研究引用元数据的脚本派生分镜或提示词时，完整正文仍传给模型。
    // 【怎么算红】把任何非空data都当成正文替代，sourceArtifact.content断言失败。
    @Test void scriptMetadataDoesNotReplaceSourceContent() throws Exception {
        var metadata=node("{\"citations\":[\"s1\"],\"researchSource\":{\"artifactId\":\"r\",\"version\":1}}");
        var source=new AgentContext.ArtifactContext("script",3,"SCRIPT","脚本","第一幕小猫帮助小兔；第二幕一起回家[s1]",metadata);
        var ref=new AgentContext.ArtifactRef("script",3,null);
        response("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"帮助\",\"visual\":\"小猫帮助小兔\",\"narration\":\"互相帮助\"}]}");
        new StoryboardGenerationSkill(gateway,json).execute(context(source,ref),node("{\"instruction\":\"生成分镜\"}"));
        var request=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).complete(any(),eq("AGENT_STORYBOARD"),anyString(),request.capture());
        assertEquals(source.content(),node(request.getValue()).path("sourceArtifact").path("content").asText());
        assertEquals(metadata,node(request.getValue()).path("sourceArtifact").path("data"));
        response("{\"title\":\"提示词\",\"content\":\"小猫帮助小兔\"}");
        new PromptOptimizationSkill(gateway,json).execute(context(source,ref),node("{\"instruction\":\"整理提示词\"}"));
        verify(gateway).complete(any(),eq("AGENT_PROMPT"),anyString(),request.capture());
        assertEquals(source.content(),node(request.getValue()).path("sourceArtifact").path("content").asText());
        assertEquals("script",node(request.getValue()).path("source").path("artifactId").asText());
        assertEquals(3,node(request.getValue()).path("source").path("version").asInt());
    }

    // 【测什么】已存在PROMPT原子能力能够成为Recipe具体计划步骤，不出现可发布却不可规划。
    // 【怎么算红】从PlanGenerationSkill.kind白名单删PROMPT，execute抛业务异常而非返回PROMPT步骤。
    @Test void promptRecipeCanProduceAnExecutablePlanKind() throws Exception {
        response("{\"title\":\"优化计划\",\"goal\":\"优化提示词\",\"constraints\":[],\"steps\":[{\"id\":\"p\",\"kind\":\"PROMPT\",\"title\":\"提示词\"}]}");
        var result=new PlanGenerationSkill(gateway,json).execute(context(null,null),node("{\"instruction\":\"规划提示词\"}"));
        assertEquals("PROMPT",result.data().path("steps").get(0).path("kind").asText());
    }

    // 【测什么】计划仅产结构化提议，不能夹带已确认或已完成状态。
    // 【怎么算红】删掉计划字段白名单，confirmed=true将被接受。
    @Test void planIsProposalWithBoundedSteps() throws Exception {
        var skill = new PlanGenerationSkill(gateway,json);
        response("{\"title\":\"宣传计划\",\"goal\":\"气象宣传片\",\"constraints\":[\"科技风格\"],\"steps\":[{\"id\":\"script\",\"kind\":\"SCRIPT\",\"title\":\"写脚本\"}]}");
        var result = skill.execute(context(null,null),node("{\"instruction\":\"先规划\"}"));
        assertEquals("PLAN",result.type()); assertEquals("SCRIPT",result.data().path("steps").get(0).path("kind").asText());
        assertFalse(result.data().has("confirmed")); assertNull(result.source());
        response("{\"title\":\"计划\",\"goal\":\"目标\",\"constraints\":[],\"steps\":[{\"id\":\"s\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}],\"confirmed\":true}");
        assertThrows(BusinessException.class,()->skill.execute(context(null,null),node("{\"instruction\":\"规划\"}")));
    }

    // 【测什么】分镜创建绑定确切脚本版本并由服务端分配稳定场景ID。
    // 【怎么算红】丢弃来源version或信任模型场景ID时断言失败。
    @Test void storyboardBindsSourceVersionAndAssignsIds() throws Exception {
        var source = new AgentContext.ArtifactContext("script",3,"SCRIPT","脚本","场景1城市、场景2雷达");
        var ref = new AgentContext.ArtifactRef("script",3,null);
        var skill = new StoryboardGenerationSkill(gateway,json);
        response("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"城市\",\"visual\":\"城市天际线\",\"narration\":\"守护城市\",\"duration\":5},{\"title\":\"雷达\",\"visual\":\"雷达扫描\",\"narration\":\"精准预报\"}]}");
        var result = skill.execute(context(source,ref),node("{\"instruction\":\"生成分镜\"}"));
        assertEquals(ref,result.source()); assertNull(result.artifactId()); assertEquals("STORYBOARD",result.type());
        assertEquals("s1",result.data().path("scenes").get(0).path("sceneId").asText());
        assertEquals("s2",result.data().path("scenes").get(1).path("sceneId").asText());
        assertThrows(BusinessException.class,()->skill.execute(context(source,null),node("{\"instruction\":\"改\",\"source\":{\"artifactId\":\"script\",\"version\":2}}")));
    }

    // 【测什么】单幕修改精确锁定来源版本，其他幕按原JSON保留，历史对象不变。
    // 【怎么算红】从模型重建全板或覆盖原data，将丢失第一幕或污染原版本。
    @Test void sceneEditPreservesEverythingElse() throws Exception {
        var data=node("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"城市\",\"visual\":\"城市\",\"narration\":\"开场\"},{\"sceneId\":\"s2\",\"title\":\"卫星\",\"visual\":\"卫星\",\"narration\":\"观测\"}]}");
        var source=new AgentContext.ArtifactContext("board",2,"STORYBOARD","原分镜","旧正文",data);
        var ref=new AgentContext.ArtifactRef("board",2,"s2");
        var skill=new StoryboardGenerationSkill(gateway,json);
        response("{\"scene\":{\"title\":\"雷达\",\"visual\":\"雷达扫描\",\"narration\":\"精准预报\",\"duration\":6}}");
        var result=skill.execute(context(source,ref),node("{\"instruction\":\"第二幕换雷达\"}"));
        assertEquals("board",result.artifactId()); assertEquals(ref,result.source()); assertEquals("原分镜",result.title());
        assertEquals(data.path("scenes").get(0),result.data().path("scenes").get(0));
        assertEquals("s2",result.data().path("scenes").get(1).path("sceneId").asText());
        assertEquals("雷达扫描",result.data().path("scenes").get(1).path("visual").asText());
        assertEquals("卫星",source.data().path("scenes").get(1).path("visual").asText());
    }

    // 【测什么】无来源、无幕ID或不在该版本中的场景不能调用LLM。
    // 【怎么算红】去掉source或scene存在性校验，将产生一次Gateway调用。
    @Test void ambiguousOrForeignSceneNeverCallsModel() throws Exception {
        var skill=new StoryboardGenerationSkill(gateway,json);
        var board=new AgentContext.ArtifactContext("b",1,"STORYBOARD","分镜","正文",node("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"幕\",\"visual\":\"画面\",\"narration\":\"旁白\"}]}"));
        for(var c:List.of(context(null,null),context(board,new AgentContext.ArtifactRef("b",1,null)),
                context(board,new AgentContext.ArtifactRef("b",1,"s2"))))
            assertThrows(BusinessException.class,()->skill.execute(c,node("{\"instruction\":\"修改\"}")));
        verifyNoInteractions(gateway);
    }

    // 【测什么】结构化结果拒绝非法JSON、额外控制字段、越界幕数和时间。
    // 【怎么算红】删掉严格JSON或场景字段/范围校验时至少一个反例不再抛错。
    @Test void rejectsMalformedAndOversizedStoryboards() throws Exception {
        var skill=new StoryboardGenerationSkill(gateway,json);
        var c=context(new AgentContext.ArtifactContext("s",1,"SCRIPT","脚本","画面"),new AgentContext.ArtifactRef("s",1,null));
        String scene="{\"title\":\"幕\",\"visual\":\"画面\",\"narration\":\"\"}";
        for(String bad:List.of("not-json", "{\"title\":\"板\",\"scenes\":[]}",
                "{\"title\":\"板\",\"scenes\":["+scene.replace("\"title\"", "\"sceneId\":\"injected\",\"title\"")+"]}",
                "{\"title\":\"板\",\"scenes\":["+scene.replace("\"title\"", "\"duration\":0,\"title\"")+"]}",
                "{\"title\":\"板\",\"scenes\":["+String.join(",",java.util.Collections.nCopies(13,scene))+"]}",
                "{\"title\":\"板\",\"scenes\":["+scene+"]} {}")) {
            response(bad);
            assertEquals(502,assertThrows(BusinessException.class,()->skill.execute(c,node("{\"instruction\":\"分镜\"}"))).getCode());
        }
    }

    // 【测什么】计划修订绑定所选PLAN版本；重复步骤、未列能力、状态夹带和空步骤均拒绝。
    // 【怎么算红】移除步骤id去重/能力白名单或来源检查将使对应断言失败。
    @Test void planRevisionAndInvalidSteps() throws Exception {
        var skill=new PlanGenerationSkill(gateway,json);
        String prefix="{\"title\":\"计划\",\"goal\":\"目标\",\"constraints\":[],\"steps\":";
        String step="{\"id\":\"s\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}";
        var ref=new AgentContext.ArtifactRef("plan",2,null);
        var c=context(new AgentContext.ArtifactContext("plan",2,"PLAN","计划","目标"),ref);
        response(prefix+"["+step+"]}");
        var result=skill.execute(c,node("{\"instruction\":\"改计划\"}"));
        assertEquals("plan",result.artifactId()); assertEquals(ref,result.source());
        for(String steps:List.of("[]", "["+step+","+step+"]", "["+step.replace("SCRIPT","DELETE")+"]",
                "["+step.replace("\"id\"", "\"status\":\"COMPLETED\",\"id\"")+"]")) {
            response(prefix+steps+"}");
            assertThrows(BusinessException.class,()->skill.execute(c,node("{\"instruction\":\"计划\"}")));
        }
        assertThrows(BusinessException.class,()->skill.validate(node("{\"instruction\":\"x\",\"source\":{\"artifactId\":\"p\",\"version\":0}}")));
    }

    // 【测什么】明确引用旧脚本版本不会被最新版本替换，分镜派生提示词保存sceneRef。
    // 【怎么算红】选择max版本或丢弃source引用时断言失败。
    @Test void textSkillsPreserveExplicitReferences() throws Exception {
        var script=new ScriptGenerationSkill(gateway,json);
        var c=new AgentContext(1L,"s","t","own","目标",null,List.of(),List.of(
                new AgentContext.ArtifactContext("s",1,"SCRIPT","旧","旧正文"),
                new AgentContext.ArtifactContext("s",2,"SCRIPT","新","新正文")),0);
        response("{\"title\":\"修订\",\"content\":\"新内容\"}");
        var result=script.execute(c,node("{\"instruction\":\"修订旧版\",\"source\":{\"artifactId\":\"s\",\"version\":1,\"sceneId\":null}}"));
        assertEquals(new AgentContext.ArtifactRef("s",1,null),result.source()); assertEquals("s",result.artifactId());
        var ref=new AgentContext.ArtifactRef("b",1,"s2");
        var board=new AgentContext.ArtifactContext("b",1,"STORYBOARD","板","画面",node("{\"scenes\":[{\"sceneId\":\"s2\",\"visual\":\"雷达\"}]}"));
        var prompt=new PromptOptimizationSkill(gateway,json).execute(context(board,ref),node("{\"instruction\":\"提炼提示词\"}"));
        assertNull(prompt.artifactId()); assertEquals(ref,prompt.source());
    }

    // 【测什么】引用只是Agent元数据，不能污染Generation报价参数；畸形引用不进入Domain。
    // 【怎么算红】不剥离source时Gateway参数包含source，或不校验version时越界调用。
    @Test void taskSkillStripsReferenceAtDomainBoundary() throws Exception {
        var domain=mock(org.example.seedancegenarate.agent.generation.AgentGenerationGateway.class);
        var skill=new ImageGenerationSkill(domain);
        var input=node("{\"model\":\"img\",\"prompt\":\"雷达\",\"source\":{\"artifactId\":\"b\",\"version\":2,\"sceneId\":\"s2\"}}");
        skill.validate(input); skill.quote(context(null,null),input);
        var captor=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(domain).quote(eq("IMAGE"),captor.capture()); assertFalse(captor.getValue().has("source"));
        assertTrue(input.has("source")); assertEquals("IMAGE",skill.descriptor().resultType());
        assertThrows(BusinessException.class,()->skill.validate(node("{\"source\":{\"artifactId\":\"b\",\"version\":-1}}")));
    }
}
