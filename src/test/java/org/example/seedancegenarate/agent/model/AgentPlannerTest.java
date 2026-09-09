package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPlannerTest {
    // 【测什么】真实GLM稀疏计划调用和完整包装无需补可选字段；原文保留、仍只规划。
    // 【怎么算红】normalize要求每个wire字段出现时，省略summary/source的响应会被拒绝。
    @Test void sparsePlanDecisionUsesOriginalBusinessOptionalFields() {
        planner=new AgentPlanner(gateway,new SkillRegistry(List.of(new PlanGenerationSkill(gateway,json))),json);
        String body="{\"decision\":{\"type\":\"CALL_SKILL\",\"text\":\"正在为您制定20秒幼儿动画的创作计划\",\"skillId\":\"plan-generation\",\"input\":{\"instruction\":\"以小猫图片f07fbb99-5ed5-4931-83b7-43e915beb5e8为参考，规划脚本、分镜、视频\"}}}";
        for(String raw:List.of(body,"`json\n"+body+"\n`","```json\n"+body+"\n```")) {
            when(gateway.completeDecision(eq(context),anyString(),anyString(),any())).thenReturn(raw);
            var result=planner.decide(context);
            assertEquals("plan-generation",result.skillId());assertNull(result.summary());
            assertFalse(result.input().has("source"));assertEquals(raw,result.rawOutput());
        }
        verify(gateway,never()).complete(any(),anyString(),anyString(),anyString());
    }

    // 【测什么】兼容省略字段后仍拒绝必填缺失/null、嵌套错误、未知字段、重复键和多对象。
    // 【怎么算红】删除required/对象/严格JSON守卫会放行坏响应；路径丢失会使断言失败。
    @Test void sparseWireStillRejectsInvalidBusinessInputsWithPrecisePaths() {
        String prefix="{\"decision\":{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":";
        for(String[] item:List.of(
                new String[]{prefix+"{}}}","$.decision.input.instruction","REQUIRED"},
                new String[]{prefix+"{\"instruction\":null}}}","$.decision.input.instruction","REQUIRED_NULL"},
                new String[]{prefix+"{\"instruction\":\"x\",\"source\":{\"artifactId\":\"s\"}}}}","$.decision.input.source.version","REQUIRED"},
                new String[]{prefix+"{\"instruction\":\"x\",\"source\":[]}}}","$.decision.input.source","OBJECT_REQUIRED"},
                new String[]{prefix+"{\"instruction\":\"x\",\"SECRET_FIELD\":true}}}","$.decision.input","UNKNOWN_FIELD"},
                new String[]{prefix+"{\"instruction\":\"x\",\"instruction\":\"y\"}}}","$","DUPLICATE_FIELD"},
                new String[]{prefix+"{\"instruction\":\"x\"}}} {}","$","TRAILING_CONTENT"},
                new String[]{"{\"decision\":{\"type\":\"ASK_USER\",\"text\":\"选\",\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\"}]}}","$.decision.options[1].label","REQUIRED"})) {
            when(gateway.completeDecision(eq(context),anyString(),anyString(),any())).thenReturn(item[0]);
            var error=assertThrows(InvalidAgentDecisionException.class,()->planner.decide(context));
            assertTrue(error.getMessage().contains(item[1]),error.getMessage());
            assertTrue(error.getMessage().contains(item[2]),error.getMessage());
            assertFalse(error.getMessage().contains("SECRET_FIELD"));assertEquals(item[0],error.rawOutput());
        }
    }

    // 【测什么】严格Provider的decision envelope及可选null还原为既有Decision，诊断保留真实wire。
    // 【怎么算红】旧Parser直接把envelope当Decision会缺type；全局删null会放过required null。
    @Test void structuredWireEnvelopePreservesRawAndOptionalSemantics() {
        String raw="{\"decision\":{\"type\":\"CALL_SKILL\",\"text\":null,\"summary\":null,\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"写脚本\",\"artifactId\":null,\"source\":null}}}";
        when(gateway.completeDecision(eq(context),anyString(),anyString(),any())).thenReturn(raw);
        var result=planner.decide(context);
        assertEquals("script-generation",result.skillId());
        assertFalse(result.input().has("source"));
        assertEquals(raw,result.rawOutput());
    }
    // 【测什么】模型只添加完整反引号包装时直接解析，保留原始响应与字符串内反引号。
    // 【怎么算红】直接readTree(raw)会拒绝全部包装；全局删反引号会破坏正文。
    @Test void acceptsOnlyWholeResponseBacktickWrappers() {
        String body="{\"type\":\"RESPOND\",\"text\":\"保留 `雷达` 与 ``` 标记\"}";
        for(String fence:List.of("`","```")) for(String tag:List.of("","json","JSON")) for(String newline:List.of("\n","\r\n")) {
            String raw=" \n"+fence+tag+newline+body+newline+fence+"\n ";respond(raw);
            var decision=parseDomain();
            assertEquals("保留 `雷达` 与 ``` 标记",decision.text());assertEquals(raw,decision.rawOutput());
        }
        respond("`json\n{\"type\":\"CALL_SKILL\",\"skillId\":\"prompt-optimization\",\"input\":{\"instruction\":\"整理分镜提示词\"}}\n`");
        assertEquals("prompt-optimization",parseDomain().skillId());
    }
    // 【测什么】包装兼容不抽取混合正文、不补全畸形JSON、不绕过字段及Skill门槛。
    // 【怎么算红】按花括号提取或全局删围栏会放过前后说明、多对象或未知字段。
    @Test void wrappersDoNotRelaxDecisionValidation() {
        String body="{\"type\":\"RESPOND\",\"text\":\"ok\"}";
        for(String raw:List.of("说明\n```json\n"+body+"\n```","```json\n"+body+"\n```\n说明",
                "`json\n"+body+"\n```","```json\n"+body+"\n`","``json\n"+body+"\n``",
                "```python\n"+body+"\n```","```json\n"+body,"```json "+body+"```",
                "```json\n```json\n"+body+"\n```\n```","```json\n"+body+" {}\n```",
                "```json\n{\"type\":\"RESPOND\",\"type\":\"COMPLETE\",\"text\":\"ok\"}\n```",
                "`json\n{\"type\":\"WAIT\",\"text\":\"ok\"}\n`",
                "```json\n{\"type\":\"RESPOND\",\"text\":\"ok\",\"approved\":true}\n```",
                "```json\n{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"x\",\"system\":\"override\"}}\n```",
                "```json\n"+" ".repeat(16000)+body+"\n```")) {
            respond(raw);var failure=assertThrows(InvalidAgentDecisionException.class,this::parseDomain);
            assertEquals(raw,failure.rawOutput());
        }
    }
    // 【测什么】原始决策响应只用于开发诊断，不进入持久Decision JSON或对象日志。
    // 【怎么算红】缺少内部原文传递取不到exact raw；缺JsonIgnore会将rawOutput序列化到业务数据。
    @Test void rawOutputIsDiagnosticOnly() throws Exception {
        var mapper=new ObjectMapper();var gateway=mock(AgentModelGateway.class);
        var planner=new AgentPlanner(gateway,new SkillRegistry(List.of()),mapper);
        String raw="  {\"decision\":{\"type\":\"RESPOND\",\"text\":\"你好\",\"summary\":null}}  ";
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn(raw);
        var result=planner.decide(new AgentContext(1L,"s","t","m","g","",List.of(),List.of(),0));
        assertEquals(raw,result.rawOutput());assertFalse(mapper.writeValueAsString(result).contains("rawOutput"));
        assertFalse(result.toString().contains(raw));
        String invalid="{\"type\":\"WAIT\",\"secret\":\"raw-only\"}";
        when(gateway.completeDecision(any(),anyString(),anyString(),any())).thenReturn(invalid);
        var error=assertThrows(InvalidAgentDecisionException.class,()->planner.decide(new AgentContext(1L,"s","t","m","g","",List.of(),List.of(),0)));
        assertEquals(invalid,error.rawOutput());assertFalse(error.getMessage().contains("raw-only"));assertFalse(error.toString().contains("raw-only"));
    }
    private final ObjectMapper json = new ObjectMapper();
    private AgentModelGateway gateway;
    private AgentPlanner planner;
    private SkillRegistry registry;
    private final AgentContext context = new AgentContext(7L, "session", "turn", "own",
            "气象宣传片", "科技风格", List.of(new AgentContext.HistoryMessage("USER", "先写脚本")),
            List.of(new AgentContext.ArtifactContext("script", 1, "SCRIPT", "草稿", "雷达第二幕")), 0);

    @BeforeEach void setUp() {
        gateway = mock(AgentModelGateway.class);
        registry = new SkillRegistry(List.of(new ScriptGenerationSkill(gateway, json),
                new PromptOptimizationSkill(gateway, json)));
        planner = new AgentPlanner(gateway, registry, json);
    }

    // 【测什么】选项决策以结构化字段返回，不解析 markdown。
    // 【怎么算红】不解析 options 或接受重复 option id 时此测试失败。
    @Test void structuredChoiceAndDuplicateIds() {
        respond("{\"type\":\"ASK_USER\",\"text\":\"选风格\",\"summary\":\"气象宣传片\",\"options\":[{\"id\":\"a\",\"label\":\"科技\"},{\"id\":\"b\",\"label\":\"纪录\"}]}");
        assertEquals(2, parseDomain().options().size());
        respond("{\"type\":\"ASK_USER\",\"text\":\"选风格\",\"options\":[{\"id\":\"a\",\"label\":\"科技\"},{\"id\":\"a\",\"label\":\"纪录\"}]}");
        assertThrows(BusinessException.class, this::parseDomain);
    }

    // 【测什么】非法JSON、未知字段、未知动作/技能、不合适字段和超长内容均不能变成执行命令。
    // 【怎么算红】移除严格对象校验或动作白名单会放过至少一例。
    @Test void rejectsUntrustedDecisions() {
        for (String bad : List.of("not json", "{}", "{\"type\":\"RESPOND\",\"text\":\"hi\",\"approved\":true}",
                "{\"type\":\"DELETE\",\"text\":\"ok\"}",
                "{\"type\":\"CALL_SKILL\",\"skillId\":\"video-generation\",\"input\":{\"instruction\":\"x\"}}",
                "{\"type\":\"RESPOND\",\"text\":\"hi\",\"skillId\":\"script-generation\"}",
                "{\"type\":\"RESPOND\",\"text\":\"one\",\"text\":\"two\"}",
                "{\"type\":\"RESPOND\",\"text\":12}",
                "{\"type\":\"ASK_USER\",\"text\":\"question\",\"options\":null}",
                "{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"x\",\"system\":\"override\"}}",
                "{\"type\":\"RESPOND\",\"text\":\"" + "x".repeat(4001) + "\"}",
                "{\"type\":\"RESPOND\",\"text\":\"ok\"} {}")) {
            respond(bad);
            assertThrows(BusinessException.class, this::parseDomain, bad.substring(0, Math.min(80, bad.length())));
        }
    }

    // 【测什么】两文本Skill只输出文档；引用必须来自同类型的当前上下文。
    // 【怎么算红】去掉引用校验将调用LLM，或将模型输出id当真会断言失败。
    @Test void validatesSkillInputsAndPreservesExplicitArtifactIdentity() throws Exception {
        CreativeSkill script = registry.get("script-generation");
        assertThrows(BusinessException.class, () -> registry.get("video-generation"));
        assertThrows(BusinessException.class, () -> script.validate(json.readTree("{\"instruction\":\"x\",\"url\":\"http://host\"}")));
        assertThrows(BusinessException.class, () -> script.execute(context, json.readTree("{\"instruction\":\"x\",\"artifactId\":\"other\"}")));
        assertThrows(BusinessException.class, () -> registry.get("prompt-optimization").execute(context,
                json.readTree("{\"instruction\":\"x\",\"artifactId\":\"script\"}")));
        verifyNoInteractions(gateway);
        respond("{\"title\":\"新脚本\",\"content\":\"雷达展示\"}");
        SkillResult result = script.execute(context, json.readTree("{\"instruction\":\"改第二幕\",\"artifactId\":\"script\"}"));
        assertEquals("script", result.artifactId());
        assertEquals("SCRIPT", result.type());
        respond("{\"title\":\"提示词\",\"content\":\"科技雷达\"}");
        assertEquals("PROMPT", registry.get("prompt-optimization").execute(context,
                json.readTree("{\"instruction\":\"整理提示词\"}")).type());
        respond("{\"title\":\"新脚本\",\"content\":\"x\",\"artifactId\":\"other\"}");
        assertThrows(BusinessException.class, () -> script.execute(context, json.readTree("{\"instruction\":\"x\"}")));
    }

    // 【测什么】结果长度和注册重复都有硬限制；格式失败不自动执行其他能力。
    // 【怎么算红】去掉结果长度或重复注册检查会变绿失败。
    @Test void boundedOutputAndRegistry() throws Exception {
        assertThrows(IllegalStateException.class, () -> new SkillRegistry(List.of(registry.get("script-generation"), registry.get("script-generation"))));
        respond("{\"title\":\"脚本\",\"content\":\"" + "x".repeat(16001) + "\"}");
        assertThrows(BusinessException.class, () -> registry.get("script-generation").execute(context, json.readTree("{\"instruction\":\"x\"}")));
        verify(gateway, times(1)).complete(eq(context), anyString(), anyString(), anyString());
    }

    // 【测什么】已选分镜的完整结构进入Planner请求，保留精确版本，计划与选择由Gateway附入。
    // 【怎么算红】移除request.selectedArtifact或选错同ID版本，此测试失败。
    @Test void plannerReceivesExactSelectedStoryboard() throws Exception {
        var ref=new AgentContext.ArtifactRef("board",2,"s2");
        var data=json.readTree("{\"scenes\":[{\"sceneId\":\"s2\",\"visual\":\"雷达\"}]}");
        var ctx=new AgentContext(7L,"s","t","own","目标",null,List.of(),List.of(
                new AgentContext.ArtifactContext("board",1,"STORYBOARD","旧","旧"),
                new AgentContext.ArtifactContext("board",2,"STORYBOARD","分镜","正文",data)),0,List.of(),
                json.readTree("{\"artifactId\":\"plan\",\"version\":3,\"confirmed\":true,\"currentStepId\":\"image\"}"),ref);
        when(gateway.completeDecision(eq(ctx),anyString(),anyString(),any())).thenReturn("{\"decision\":{\"type\":\"RESPOND\",\"text\":\"准备生成第二幕\",\"summary\":null}}");
        planner.decide(ctx);
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).completeDecision(eq(ctx),anyString(),captor.capture(),any());
        var request=json.readTree(captor.getValue());
        assertEquals(2,request.path("selectedArtifact").path("version").asInt());
        assertEquals(data,request.path("selectedArtifact").path("data"));
    }

    private void respond(String response) {
        domainRaw=response;
        when(gateway.complete(eq(context), anyString(), anyString(), anyString())).thenReturn(response);
    }

    // 【测什么】引用脚本的data仅来源元数据时仍向Planner提供正文，分镜结构则不重复正文。
    // 【怎么算红】只要data非空就remove(content)会使带来源脚本正文断言失败。
    @Test void selectedScriptMetadataDoesNotReplaceContent() throws Exception {
        var ctx=new AgentContext(7L,"s","t","own","目标",null,List.of(),List.of(
                new AgentContext.ArtifactContext("script",2,"SCRIPT","宣传脚本","第二幕：气象雷达",json.readTree("{\"citations\":[\"s1\"]}"))),
                0,List.of(),null,new AgentContext.ArtifactRef("script",2,null));
        when(gateway.completeDecision(eq(ctx),anyString(),anyString(),any()))
                .thenReturn("{\"decision\":{\"type\":\"RESPOND\",\"text\":\"正在查看\",\"summary\":null}}");
        planner.decide(ctx);
        var request=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).completeDecision(eq(ctx),anyString(),request.capture(),any());
        assertEquals("第二幕：气象雷达",json.readTree(request.getValue()).path("selectedArtifact").path("content").asText());
    }

    // 【测什么】Prompt的完整示例使用同一wire协议，选项数量和nullable字段合法。
    // 【怎么算红】恢复裸Decision、单个Option或遗漏summary示例时此测试失败。
    @Test void promptExamplesMatchWireShape() throws Exception {
        when(gateway.completeDecision(eq(context),anyString(),anyString(),any()))
                .thenReturn("{\"decision\":{\"type\":\"RESPOND\",\"text\":\"ok\",\"summary\":null}}");
        planner.decide(context);
        var policy=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).completeDecision(eq(context),policy.capture(),anyString(),any());
        int examples=0;
        for(String line:policy.getValue().split("\n")) if(line.startsWith("RESPOND:")||line.startsWith("ASK_USER:")||line.startsWith("COMPLETE:")) {
            String example=line.substring(line.indexOf('{'),line.lastIndexOf('}')+1);
            assertTrue(json.readTree(example).path("decision").has("summary"));
            when(gateway.completeDecision(eq(context),anyString(),anyString(),any())).thenReturn(example);
            assertNotNull(planner.decide(context));examples++;
        }
        assertEquals(3,examples);
    }

    private String domainRaw;
    /** Test original domain parser independently of the newly mandatory Provider envelope. */
    private AgentDecision parseDomain() {
        try {
            var method=AgentPlanner.class.getDeclaredMethod("parse",String.class);method.setAccessible(true);
            return (AgentDecision)method.invoke(planner,domainRaw);
        }catch(java.lang.reflect.InvocationTargetException e){
            if(e.getCause() instanceof InvalidAgentDecisionException invalid)throw new InvalidAgentDecisionException(invalid.getMessage(),domainRaw);
            if(e.getCause() instanceof RuntimeException failure)throw failure;
            throw new AssertionError(e.getCause());
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    // 【测什么】解析失败提供具体规则和可信字段路径，不泄漏模型文本、任意键名或堆栈。
    // 【怎么算红】恢复统一invalid()提示或直接返回Jackson原异常，诊断及脱敏断言失败。
    @Test void preciseSafeDecisionDiagnostics() {
        assertDiagnostic("[]", "$", "OBJECT_REQUIRED");
        assertDiagnostic("{\"type\":\"SECRET_ACTION\",\"text\":\"ok\"}", "$.type", "ACTION_NOT_ALLOWED");
        assertDiagnostic("{\"type\":\"RESPOND\",\"text\":\"ok\",\"SECRET_FIELD\":true}", "$", "UNKNOWN_FIELD");
        assertDiagnostic("{\"type\":\"RESPOND\",\"text\":\"ok\",\"text\":\"SECRET_VALUE\"}", "$", "DUPLICATE_FIELD");
        assertDiagnostic("{\"type\":\"RESPOND\",\"text\":\"ok\"} {}", "$", "TRAILING_CONTENT");
        assertDiagnostic("{\"type\":\"RESPOND\",\"text\":SECRET_VALUE}", "$", "JSON_SYNTAX");
        assertDiagnostic("{\"type\":\"RESPOND\",\"text\":\"" + "x".repeat(4001) + "\"}", "$.text", "TEXT_LIMIT");
        assertDiagnostic("{\"type\":\"ASK_USER\",\"text\":\"?\",\"options\":[]}", "$.options", "OPTION_COUNT");
        assertDiagnostic("{\"type\":\"ASK_USER\",\"text\":\"?\",\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\"}]}", "$.options[1].label", "REQUIRED");
        assertDiagnostic("{\"type\":\"ASK_USER\",\"text\":\"?\",\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"不合法\",\"label\":\"B\"}]}", "$.options[1].id", "OPTION_ID_FORMAT");
        assertDiagnostic("{\"type\":\"ASK_USER\",\"text\":\"?\",\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"a\",\"label\":\"B\"}]}", "$.options[1].id", "OPTION_ID_DUPLICATE");
    }

    // 【测什么】Skill参数错误从已注册schema定位字段，不回显参数值，未知技能与参数错误可区分。
    // 【怎么算红】恢复统一skillId/input报错或只检查顶层input，具体嵌套字段断言失败。
    @Test void skillDiagnosticsUseRegisteredSchema() {
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"SECRET_SKILL\",\"input\":{}}", "$.skillId", "SKILL_NOT_AVAILABLE");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{}}", "$.input.instruction", "REQUIRED");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"SECRET_VALUE\",\"source\":{\"artifactId\":\"s\",\"version\":0}}}", "$.input.source.version", "MINIMUM");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"SECRET_VALUE\",\"SECRET_FIELD\":true}}", "$.input", "UNKNOWN_FIELD");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"x\",\"artifactId\":\"a\",\"source\":{\"artifactId\":\"s\",\"version\":1}}}", "$.input.source", "REFERENCE_CONFLICT");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"x\",\"source\":{\"artifactId\":\"s\",\"version\":1.5}}}", "$.input.source.version", "NUMBER_TYPE");
    }

    // 【测什么】模型动态约束的解释不回显模型名或值，未认识的新schema仍拒绝而不自创解释。
    // 【怎么算红】去掉enum/maximum诊断或输出原始业务异常会使规则码与敏感值断言失败。
    @Test void capabilityDiagnosticsAndUnknownSchemaFallback() throws Exception {
        CreativeSkill skill = mock(CreativeSkill.class);
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{\"model\":{\"type\":\"string\",\"enum\":[\"valid\"]},\"duration\":{\"type\":\"integer\",\"maximum\":120}}}");
        when(skill.descriptor()).thenReturn(new SkillDescriptor("test-skill", "1", "test", schema));
        doThrow(BusinessException.badRequest("SECRET_VALUE")).when(skill).validate(any());
        planner = new AgentPlanner(gateway, new SkillRegistry(List.of(skill)), json);
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"test-skill\",\"input\":{\"model\":\"SECRET_VALUE\"}}", "$.input.model", "ENUM_VALUE");
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"test-skill\",\"input\":{\"model\":\"valid\",\"duration\":121}}", "$.input.duration", "MAXIMUM");
        doThrow(BusinessException.badRequest("画幅不在模型支持范围内")).when(skill).validate(any());
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"test-skill\",\"input\":{\"model\":\"valid\"}}", "$.input.ratio", "MODEL_CAPABILITY");
        when(skill.descriptor()).thenReturn(new SkillDescriptor("test-skill", "1", "test", json.readTree("{\"type\":\"object\",\"properties\":{\"SECRET_FIELD\":{\"type\":\"string\"}}}")));
        doThrow(BusinessException.badRequest("SECRET_VALUE")).when(skill).validate(any());
        assertDiagnostic("{\"type\":\"CALL_SKILL\",\"skillId\":\"test-skill\",\"input\":{}}", "$.input", "SKILL_INPUT_INVALID");
        verify(skill, never()).execute(any(), any());
    }

    // 【测什么】技能实现故障和非参数业务异常保留原分类，不能被标为模型格式错而自动修正。
    // 【怎么算红】parse恢复catch(Exception)或把所有BusinessException包装后，这两次身份断言失败。
    @Test void skillImplementationErrorsAreNotModelFormatErrors() {
        CreativeSkill broken = spy(registry.get("script-generation"));
        planner = new AgentPlanner(gateway, new SkillRegistry(List.of(broken)), json);
        respond("{\"type\":\"CALL_SKILL\",\"skillId\":\"script-generation\",\"input\":{\"instruction\":\"x\"}}");
        var bug = new IllegalStateException("implementation failure");
        doThrow(bug).when(broken).validate(any());
        assertSame(bug, assertThrows(IllegalStateException.class, this::parseDomain));
        var unavailable = new BusinessException(503, "unavailable");
        doThrow(unavailable).when(broken).validate(any());
        assertSame(unavailable, assertThrows(BusinessException.class, this::parseDomain));
    }

    private void assertDiagnostic(String raw, String path, String code) {
        respond(raw);
        var failure = assertThrows(InvalidAgentDecisionException.class, this::parseDomain);
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
        assertTrue(failure.getMessage().length() <= 512);
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("\n"));
        assertNull(failure.getCause());
    }
}
