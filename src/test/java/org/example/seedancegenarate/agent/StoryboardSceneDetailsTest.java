package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoryboardSceneDetailsTest {
    final ObjectMapper json=new ObjectMapper();
    AgentContext context() {
        return new AgentContext(1L,"session","turn","model","故事",null,List.of(),List.of(
                new AgentContext.ArtifactContext("script",1,"SCRIPT","故事","完整原文"),
                new AgentContext.ArtifactContext("cat",2,"IMAGE","小猫","")),0,List.of(),null,new AgentContext.ArtifactRef("script",1,null));
    }
    com.fasterxml.jackson.databind.node.ObjectNode scene() throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
            {"title":"相遇","visual":"小猫向朋友招手","narration":"太阳出来了",
             "characters":[{"name":"小猫","appearance":"橘色毛发","wardrobe":"蓝围巾","referenceImage":{"artifactId":"cat","version":2}}],
             "shot":{"action":"招手","framing":"中景","cameraMovement":"缓慢推近","startState":"坐着","endState":"站起"},
             "sound":{"narration":"太阳出来了","dialogue":[{"speaker":"小猫","text":"你好"},{"speaker":"朋友","text":"早上好"}],"ambience":"鸟鸣"}}
            """);
    }
    // 【测什么】新增结构在真实Skill输出中保存，旧分镜仍可生成。
    // 【怎么算红】移除scene允许字段或深拷贝，新增字段输出校验抛错/消失。
    @Test void skillPersistsStructuredDetailsAndAcceptsLegacy() throws Exception {
        var gateway=mock(AgentModelGateway.class);var skill=new StoryboardGenerationSkill(gateway,json);
        for(boolean structured:List.of(true,false)) {
            var s=scene();if(!structured)s.remove(List.of("characters","shot","sound"));
            var output=json.createObjectNode().put("title","小故事");output.putArray("scenes").add(s);
            when(gateway.complete(any(),eq("AGENT_STORYBOARD"),anyString(),anyString())).thenReturn(output.toString());
            var result=skill.execute(context(),json.createObjectNode().put("instruction","写分镜"));
            assertEquals("s1",result.data().path("scenes").get(0).path("sceneId").asText());
            assertEquals(structured,result.data().path("scenes").get(0).has("characters"));
            if(structured)assertEquals("早上好",result.data().path("scenes").get(0).path("sound").path("dialogue").get(1).path("text").asText());
        }
    }
    // 【测什么】旁白两处矛盾、未知结构、超量角色、伪造或旧版本引用拒绝。
    // 【怎么算红】删除对应结构/引用守卫，至少一个assertThrows接受坏分镜。
    @Test void malformedAndInventedReferencesFailClosed() throws Exception {
        var mismatch=scene();((com.fasterxml.jackson.databind.node.ObjectNode)mismatch.get("sound")).put("narration","不同旁白");
        assertThrows(BusinessException.class,()->StoryboardSceneDetails.validate(mismatch));
        var extra=scene();((com.fasterxml.jackson.databind.node.ObjectNode)extra.get("shot")).put("execute",true);
        assertThrows(BusinessException.class,()->StoryboardSceneDetails.validate(extra));
        var many=scene();var array=(com.fasterxml.jackson.databind.node.ArrayNode)many.get("characters");
        for(int i=0;i<8;i++)array.addObject().put("name","c"+i);
        assertThrows(BusinessException.class,()->StoryboardSceneDetails.validate(many));
        StoryboardSceneDetails.validateReferences(scene(),context());
        for(String id:List.of("foreign","script","cat")) {
            var s=scene();((com.fasterxml.jackson.databind.node.ObjectNode)s.path("characters").get(0).path("referenceImage")).put("artifactId",id).put("version",1);
            assertThrows(BusinessException.class,()->StoryboardSceneDetails.validateReferences(s,context()));
        }
    }
    // 【测什么】声音规则保持说话人归属，旧多行旁白逐行提取而非整段锁死。
    // 【怎么算红】只返回台词不带speaker或不拆行，精确列表不匹配。
    @Test void speechKeepsSpeakerAndLegacyLineBoundaries() throws Exception {
        assertEquals(List.of("小猫:你好","朋友:早上好","太阳出来了"),StoryboardSceneDetails.spokenLines(scene()));
        assertEquals(List.of("第一句","第二句"),StoryboardSceneDetails.spokenLines(json.readTree("{\"narration\":\"第一句\\n第二句\"}")));
    }
}
