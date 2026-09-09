package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTextOutputContractTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentModelGateway gateway=mock(AgentModelGateway.class);
    AgentContext context(){return new AgentContext(1L,"s","t","own","创作",null,List.of(),List.of(),0,List.of(),null,null);}
    void response(String raw){when(gateway.complete(any(),anyString(),anyString(),anyString())).thenReturn(raw);}

    // 【测什么】三种文字作品统一接受完整JSON围栏，但不提取混杂说明中的对象。
    // 【怎么算红】Plan/Script仍用原始readTree或解析器任意提取花括号时断言失败。
    @Test void exactFencesWorkAcrossThreeSkillsButProseDoesNot() throws Exception {
        var input=json.readTree("{\"instruction\":\"创作\"}");
        response("```json\n{\"title\":\"计划\",\"goal\":\"目标\",\"constraints\":[],\"steps\":[{\"id\":\"s\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}]}\n```");
        assertEquals("PLAN",new PlanGenerationSkill(gateway,json).execute(context(),input).type());
        response("`json\n{\"title\":\"脚本\",\"content\":\"正文\"}\n`");
        assertEquals("SCRIPT",new ScriptGenerationSkill(gateway,json).execute(context(),input).type());
        var source=new AgentContext.ArtifactContext("script",1,"SCRIPT","脚本","正文");
        var context=new AgentContext(1L,"s","t","own","创作",null,List.of(),List.of(source),0,List.of(),null,new AgentContext.ArtifactRef("script",1,null));
        response("```json\n{\"title\":\"分镜\",\"scenes\":[{\"title\":\"第一幕\",\"visual\":\"校园\",\"narration\":\"\"}]}\n```");
        assertEquals("STORYBOARD",new StoryboardGenerationSkill(gateway,json).execute(context,input).type());
        response("这里是作品 {\"title\":\"脚本\",\"content\":\"正文\"}");
        var e=assertThrows(SkillOutputContractException.class,()->new ScriptGenerationSkill(gateway,json).execute(context(),input));
        assertTrue(e.getMessage().contains("JSON_SYNTAX"));assertNotNull(e.getCause());assertFalse(e.getMessage().contains("这里是作品"));
    }

    // 【测什么】未知字段、重复key、尾随对象和字段越界保留严格校验且提供安全定位。
    // 【怎么算红】放宽duplicate/trailing检查或仍抛通用502时类型与字段断言失败。
    @Test void malformedDocumentsKeepSafeFieldReason() throws Exception {
        var skill=new ScriptGenerationSkill(gateway,json);var input=json.readTree("{\"instruction\":\"脚本\"}");
        for(String raw:List.of("{\"title\":\"x\",\"title\":\"y\",\"content\":\"z\"}","{\"title\":\"x\",\"content\":\"z\"} {}","{\"title\":\"x\",\"content\":\"z\",\"extra\":true}")) {
            response(raw);assertThrows(SkillOutputContractException.class,()->skill.execute(context(),input));
        }
        response("{\"title\":\"\",\"content\":\"z\"}");
        var error=assertThrows(SkillOutputContractException.class,()->skill.execute(context(),input));
        assertTrue(error.getMessage().contains("title"));assertNotNull(error.getCause());
        var refInput=json.readTree("{\"instruction\":\"脚本\",\"source\":{\"artifactId\":\"foreign\",\"version\":1}}");
        clearInvocations(gateway);
        assertFalse(assertThrows(BusinessException.class,()->skill.execute(context(),refInput)) instanceof SkillOutputContractException);
        verifyNoInteractions(gateway);
    }

    // 【测什么】分镜模型编造角色参考IMAGE不进入格式修复，引用权限仍由业务层拒绝。
    // 【怎么算红】把validateReferences放回输出catch后异常会变成SkillOutputContractException。
    @Test void generatedForeignImageReferenceRemainsBusinessRejection() throws Exception {
        var source=new AgentContext.ArtifactContext("script",1,"SCRIPT","脚本","正文");
        var context=new AgentContext(1L,"s","t","own","创作",null,List.of(),List.of(source),0,List.of(),null,new AgentContext.ArtifactRef("script",1,null));
        response("{\"title\":\"分镜\",\"scenes\":[{\"title\":\"第一幕\",\"visual\":\"校园\",\"narration\":\"\",\"characters\":[{\"name\":\"小猫\",\"referenceImage\":{\"artifactId\":\"foreign\",\"version\":1}}]}]}");
        var input=json.readTree("{\"instruction\":\"分镜\"}");
        var error=assertThrows(BusinessException.class,()->new StoryboardGenerationSkill(gateway,json).execute(context,input));
        assertFalse(error instanceof SkillOutputContractException);
    }
}
