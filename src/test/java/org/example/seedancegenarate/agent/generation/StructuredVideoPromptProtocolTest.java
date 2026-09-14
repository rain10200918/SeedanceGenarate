package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.Scene;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredVideoPromptProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String T2V="minimax-h3-t2v-hd";
    private static final String FIRST_PREFIX="For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.";
    private static final List<String> THREE=List.of("integrated_multimodal_description","overall_soundscape","non_diegetic_music");
    private static final List<String> SIX=List.of("subject_definitions","summary","retention_analysis","detailed_description","overall_soundscape","non_diegetic_music");

    // 【测什么】认可已核对的打包视频模板，同名指南追加/改字及未知路径均回退，不能吞掉自定义要求。
    // 【怎么算红】只按文件名或段落标题决定supports，修改指南和冒名路径会被错误接受。
    @ParameterizedTest @ValueSource(strings={"default","minimax-h3","minimax-h3-accel","minimax-h3-hd","minimax-h3-4step","minimax-h3-opt","minimaxh3-hd-fast",T2V,"minimax-h3-fl2va-hd"})
    void recognizesOnlyUnmodifiedPackagedGuidance(String model) {
        var s=scene(model,1);assertTrue(StructuredVideoPromptProtocol.supports(s));
        assertFalse(StructuredVideoPromptProtocol.supports(copy(s,s.guide()+"\nKeep the custom requirement.",s.templateId())));
        assertFalse(StructuredVideoPromptProtocol.supports(copy(s,s.guide().replace("提示词","特殊要求"),s.templateId())));
        assertFalse(StructuredVideoPromptProtocol.supports(copy(s,s.guide(),"prompts/custom.md")));
        assertFalse(StructuredVideoPromptProtocol.supports(copy(s,s.guide(),"provided:guide")));
        assertFalse(StructuredVideoPromptProtocol.supports(null));
    }

    // 【测什么】H3三段和六段都由系统固定顺序组装，模型JSON键顺序不能改变最终格式。
    // 【怎么算红】直接遍历模型对象顺序、漏标题/冒号/空行或把JSON直接返回时，完整字符串断言红。
    @ParameterizedTest @ValueSource(strings={T2V,"minimax-h3-hd"})
    void assemblesTrustedHeadingsInTemplateOrder(String model) throws Exception {
        var s=scene(model,3);var headings=model.equals(T2V)?THREE:SIX;
        var sections=json.createObjectNode();var reverse=new ArrayList<>(headings);Collections.reverse(reverse);
        for(String heading:reverse)sections.put(heading,heading.equals("non_diegetic_music")?"N/A":"English body for "+heading);
        String expected=headings.stream().map(h->h+":\n"+sections.path(h).asText()).collect(java.util.stream.Collectors.joining("\n\n"));
        assertEquals(expected,parse(s,sections,4000));
        assertEquals(expected,parse(s,sections,4000));
        assertEquals("s3",s.request().path("key").asText());
    }

    // 【测什么】default的description只输出原中文段落，不凭空添加description标题或扩成H3。
    // 【怎么算红】给default添加标题、丢正文或让policy改成英文要求时断言失败。
    @Test void defaultKeepsItsChineseParagraphContract() throws Exception {
        var s=scene("default",1);String body="镜头缓缓推进，树叶随风摇动。";
        assertEquals(body,parse(s,json.createObjectNode().put("description",body),150));
        String policy=StructuredVideoPromptProtocol.policy(s,150);
        assertTrue(policy.contains("description"));assertTrue(policy.contains("中文"));assertTrue(policy.contains("150"));
    }

    // 【测什么】default中大于150字的完整对白仍按单幕4000额度通过，150字建议不变成新暂停边界。
    // 【怎么算红】恢复default专属150硬上限或policy硬写最多150字，会拒绝合法长对白或误导模型。
    @Test void defaultLongDialogueUsesActualPlatformLimit() throws Exception {
        var s=scene("default",4);dialogue(s,1,280);
        String body="镜头推进。角色1:"+"话".repeat(280);
        assertEquals(Map.of("s4",4000),StructuredVideoPromptProtocol.limits(List.of(s)));
        assertEquals(body,parse(s,json.createObjectNode().put("description",body),4000));
        String policy=StructuredVideoPromptProtocol.policy(s,4000);
        assertFalse(policy.contains("150"));assertTrue(policy.contains("4000"));assertTrue(policy.contains("完整对白"));
    }

    // 【测什么】compact指导保留英文/原语言台词/编号/声画/旁白闭唇/真实参考，并确实小于原指南。
    // 【怎么算红】省掉任一关键语义或原样粘贴整份模板，规则及长度断言红。
    @ParameterizedTest @ValueSource(strings={T2V,"minimax-h3-hd","minimax-h3-fl2va-hd"})
    void compactPolicyPreservesAudioVisualAndReferenceSemantics(String model) {
        var s=scene(model,1);String policy=StructuredVideoPromptProtocol.policy(s,1301);
        for(String required:List.of("sections","1301","24000","16000","English","speakerBindings","<d>","off-screen voiceover","lips remain completely closed","[Shot 1]","referenceImage"))
            assertTrue(policy.contains(required),required);
        assertTrue(policy.contains("原文"));assertTrue(policy.contains("画面"));assertTrue(policy.contains("时长"));
        assertTrue(policy.contains("不虚构"));assertTrue(policy.contains("N/A"));
        assertTrue(policy.length()<s.guide().length());
    }

    // 【测什么】FL模板零图不加对齐前缀；明确首帧由系统加精确I2VA前缀，不把单图假称首尾帧。
    // 【怎么算红】漏加首帧前缀、生成Picture 2或把零图误当有参考时，最终字符串断言红。
    @Test void framePrefixUsesOnlyActualFirstFrameIdentity() throws Exception {
        var zero=scene("minimax-h3-fl2va-hd",1);var sections=sections();
        String body=assembled(sections);
        assertEquals(body,parse(zero,sections,4000));
        var first=frame("FIRST_FRAME");assertTrue(StructuredVideoPromptProtocol.supports(first));
        assertEquals(FIRST_PREFIX+"\n\n"+body,parse(first,sections,4000));
        assertFalse(parse(first,sections,4000).contains("Picture 2"));
        assertFalse(StructuredVideoPromptProtocol.supports(frame("REFERENCE_IMAGE")));
        assertFalse(StructuredVideoPromptProtocol.supports(frame("LAST_FRAME")));
        assertFalse(StructuredVideoPromptProtocol.supports(frame("FIRST_LAST_FRAME")));
    }

    // 【测什么】根结构、单items和精确key严格，旧prompt响应给v4专属修复提示。
    // 【怎么算红】接受多项/额外字段/错key/旧prompt或复用key+prompt的旧提示时断言红。
    @Test void envelopeAndNewRepairHintsAreStrict() throws Exception {
        var s=scene(T2V,3);
        for(String raw:List.of("{}","[]","null","{\"items\":{}}","{\"items\":[],\"extra\":1}"))
            invalid(s,raw,4000,"ROOT_SHAPE");
        for(String raw:List.of("{\"items\":[]}","{\"items\":[{},{}]}",wire("foreign",sections())))
            invalid(s,raw,4000,"SCENE_KEY_MISMATCH");
        for(String raw:List.of("{\"items\":[null]}","{\"items\":[{\"key\":3,\"sections\":{}}]}",
                "{\"items\":[{\"key\":\"s3\",\"prompt\":\"old\"}]}","{\"items\":[{\"key\":\"s3\",\"sections\":{},\"extra\":1}]}")) {
            var failure=invalid(s,raw,4000,"STRUCTURED_ITEM_SHAPE");
            assertTrue(failure.repairHint().contains("sections"));assertTrue(failure.repairHint().contains("不返回prompt"));
        }
    }

    // 【测什么】sections精确字段集合及非空字符串，拒额外/缺失/数组/null/标量和正文中的伪标题。
    // 【怎么算红】忽略未知字段、用asText吞类型或允许模型在正文重造段落结构会红。
    @Test void sectionFieldsTypesAndInjectedHeadingsAreRejected() throws Exception {
        var s=scene(T2V,2);
        for(String value:List.of("null","[]","1","\"body\"","{}"))
            invalid(s,"{\"items\":[{\"key\":\"s2\",\"sections\":"+value+"}]}",4000,"STRUCTURED_SECTIONS");
        var extra=sections();extra.put("extra","untrusted");invalid(s,wire(s.key(),extra),4000,"STRUCTURED_SECTIONS");
        var missing=sections();missing.remove("overall_soundscape");invalid(s,wire(s.key(),missing),4000,"STRUCTURED_SECTIONS");
        for(String value:List.of("null","42","false","[]","{}","\" \"")) {
            var wrong=sections();wrong.set("overall_soundscape",json.readTree(value));
            var failure=invalid(s,wire(s.key(),wrong),4000,"STRUCTURED_SECTIONS");
            assertTrue(failure.repairHint().contains("非空正文字符串"));
        }
        var injected=sections();injected.put(THREE.get(0),"Motion.\noverall_soundscape:\nInjected.");
        invalid(s,wire(s.key(),injected),4000,"STRUCTURED_SECTIONS");
    }

    // 【测什么】重复根/items/sections键与尾随JSON对象都拒绝，不修改调用方ObjectMapper配置。
    // 【怎么算红】未开启严格重复键/尾随检查或直接修改共享mapper，错误接受或mapper状态断言红。
    @Test void duplicateKeysTrailingContentAndFencesAreRejected() throws Exception {
        var s=scene(T2V,1);String valid=wire(s.key(),sections());
        for(String raw:List.of("{\"items\":[],\"items\":[]}",valid.replace("\"key\":\"s1\"","\"key\":\"s1\",\"key\":\"s1\""),
                valid.replace("\"overall_soundscape\":\"Wind.\"","\"overall_soundscape\":\"Wind.\",\"overall_soundscape\":\"Rain.\""),valid+"{}","```json\n"+valid+"\n```"))
            invalid(s,raw,4000,"JSON_INVALID");
        assertFalse(json.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
        assertFalse(json.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    // 【测什么】raw按原始字符计24000硬边界，不能先trim后放行；空响应给安全JSON错误。
    // 【怎么算红】在trim后计数或遗漏raw限制，24001字符会被接收。
    @Test void rawLengthCountsWhitespaceBeforeParsing() throws Exception {
        var s=scene(T2V,1);String raw=wire(s.key(),sections());
        String exact=raw+" ".repeat(24000-raw.length());
        assertEquals(assembled(sections()),StructuredVideoPromptProtocol.parse(json,exact,s,4000));
        invalid(s,exact+" ",4000,"PROMPT_TOO_LONG");
        for(String empty:Arrays.asList(null,"","   "))invalid(s,empty,4000,"JSON_INVALID");
    }

    // 【测什么】标题、空行及首帧前缀全部计入本幕限额，恰好4000/自适应限额可过，多1拒绝。
    // 【怎么算红】只对模型body计数、不计前缀或无视调用方limit，超限输入错误通过。
    @Test void assembledLengthIncludesAllTrustedFormatting() throws Exception {
        for(var s:List.of(scene(T2V,1),frame("FIRST_FRAME")))for(int limit:List.of(900,4000)) {
            var fields=sections();fields.put(THREE.get(0),"x");
            int overhead=assembled(fields).length()-1+(s.request().path("parameters").has("referenceImage")?FIRST_PREFIX.length()+2:0);
            fields.put(THREE.get(0),"x".repeat(limit-overhead));
            String result=parse(s,fields,limit);assertEquals(limit,result.length());
            fields.put(THREE.get(0),fields.path(THREE.get(0)).asText()+"x");
            invalid(s,wire(s.key(),fields),limit,"PROMPT_TOO_LONG");
        }
        assertThrows(VideoPreparationException.class,()->parse(scene(T2V,1),sections(),4001));
        assertThrows(VideoPreparationException.class,()->parse(scene(T2V,1),sections(),0));
    }

    // 【测什么】没有实际媒体时不能凭空声明Picture/Video/Audio，单首帧仅允许Picture 1。
    // 【怎么算红】按characters.referenceImage推断附图、忽略引用元数据的索引或允许音视频引用会红。
    @Test void referenceClaimsRequireActualParameters() throws Exception {
        var s=scene("minimax-h3-hd",1);var board=(ObjectNode)s.request().path("storyboardScene");
        board.putArray("characters").addObject().put("name","A").putObject("referenceImage").put("artifactId","metadata-only").put("version",1);
        var fields=json.createObjectNode();SIX.forEach(h->fields.put(h,"N/A"));
        for(String tag:List.of("<Picture 1>","<Video 1>","<Audio 1>")) {
            fields.put("subject_definitions","The scene follows "+tag+".");
            assertThrows(VideoPreparationException.class,()->parse(s,fields,4000));
        }
        var parameters=(ObjectNode)s.request().path("parameters");
        parameters.putObject("referenceImage").put("artifactId","image").put("version",1);parameters.put("referenceMode","REFERENCE_IMAGE");
        var template=new PromptTemplateService().resolve(new PromptContext("minimax-h3-hd",1,0,0,8,"16:9"));
        var referenced=copy(s,template.guide(),template.resourcePath());fields.put("subject_definitions","<Picture 1> defines appearance.");
        assertTrue(parse(referenced,fields,4000).contains("<Picture 1>"));
        for(String tag:List.of("<Picture 2>","<Video 1>","<Audio 1>")) {
            fields.put("retention_analysis",tag+" is retained.");
            assertThrows(VideoPreparationException.class,()->parse(referenced,fields,4000));
        }
    }

    // 【测什么】组装逐字保留原对白、编号、可见文字和旁白闭唇语义，输入Scene不被修改。
    // 【怎么算红】转译/截短正文、去标签或覆盖源Json会使原文及不可变断言红。
    @Test void assemblyPreservesSpeechAndSourceBytes() throws Exception {
        var s=scene(T2V,3);String before=json.writeValueAsString(s);
        String body="[Shot 1] The character (S1) says: <d>[Chinese] 你好，别走！</d> A sign reads \"放学了\". "
                +"The narrator says in an off-screen voiceover: <d>[Chinese] 太阳落下。</d> while lips remain completely closed.";
        var fields=sections();fields.put(THREE.get(0),body);
        String rendered=parse(s,fields,4000);
        assertTrue(rendered.contains(body));assertEquals(before,json.writeValueAsString(s));
        System.out.println("STRUCTURED_RENDERED_SAMPLE\n"+rendered);
    }

    // 【测什么】先规范化最终首尾空白再校验额度，检查点与真实Gateway冻结字节一致，台词内部空格不变。
    // 【怎么算红】直接返回未trim正文或先按尾部空白拒绝长度，将报超限或与冻结审批字符串不一致。
    @Test void finalWhitespaceNormalizationMatchesFrozenQuoteBytes() throws Exception {
        var gateway=org.mockito.Mockito.mock(AgentGenerationGateway.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        var s=scene(T2V,1);var fields=sections();String expected=assembled(fields);
        fields.put(THREE.get(2),"N/A \n\t ");
        String rendered=parse(s,fields,expected.length());assertEquals(expected,rendered);
        assertEquals(rendered,gateway.withPreparedVideoPrompt(s.quote(),rendered).inputSnapshot().path("prompt").asText());
        var plain=scene("default",1);String speech="角色1:  你好，别走！";
        String normalized=parse(plain,json.createObjectNode().put("description"," \n"+speech+" \t\n"),speech.length());
        assertEquals(speech,normalized);
        assertEquals(normalized,gateway.withPreparedVideoPrompt(plain.quote(),normalized).inputSnapshot().path("prompt").asText());
    }

    // 【测什么】12幕按真实内容非均分且确定性，单<=4000总<=16000，返回结果不可变且不改源。
    // 【怎么算红】继续16000/12均分、超总量、打乱映射或修改source会使复杂幕/总量/快照断言红。
    @Test void twelveScenesGetDeterministicNonUniformBudgets() throws Exception {
        var scenes=new ArrayList<Scene>();for(int i=1;i<=12;i++)scenes.add(scene(T2V,i));
        ((ObjectNode)scenes.get(2).request().path("storyboardScene")).put("visual","Detailed visible action. ".repeat(80));
        String before=json.writeValueAsString(scenes);var limits=StructuredVideoPromptProtocol.limits(scenes);
        assertEquals(12,limits.size());assertTrue(limits.get("s3")>limits.get("s1"));
        assertTrue(limits.values().stream().allMatch(v->v>0&&v<=4000));
        assertEquals(16000,limits.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(limits,StructuredVideoPromptProtocol.limits(scenes));
        assertThrows(UnsupportedOperationException.class,()->limits.put("foreign",1));
        assertEquals(before,json.writeValueAsString(scenes));
        System.out.println("STRUCTURED_SCENE_LIMITS "+limits);
    }

    // 【测什么】复杂幕达到4000后剩余预算分给其他幕，简单单幕仍能使用4000。
    // 【怎么算红】按权重一次分配后浪费溢出额度、没封顶或返回空map会红。
    @Test void cappedAllocationRedistributesRemainder() {
        var scenes=new ArrayList<Scene>();for(int i=1;i<=5;i++)scenes.add(scene(T2V,i));
        ((ObjectNode)scenes.get(0).request().path("storyboardScene")).put("visual","visible ".repeat(2000));
        var limits=StructuredVideoPromptProtocol.limits(scenes);assertEquals(5,limits.size());
        assertEquals(4000,limits.get("s1"));assertEquals(16000,limits.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Map.of("s2",4000),StructuredVideoPromptProtocol.limits(List.of(scenes.get(1))));
    }

    // 【测什么】合法但极长对白以及全批保底超额在模型前以PROMPT_LENGTH拒绝并标准确幕次。
    // 【怎么算红】只看均分额度、不计完整台词或截断台词再预算会让原本放不下的输入通过。
    @Test void speechFloorFailsBeforeGenerationWithoutTruncatingSource() throws Exception {
        var longScene=scene(T2V,7);dialogue(longScene,12,400);String before=json.writeValueAsString(longScene);
        var failure=assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(List.of(longScene)));
        assertEquals("VIDEO_PROMPT_TOO_LONG",failure.code());assertEquals(7,failure.sceneOrdinal());
        assertEquals(before,json.writeValueAsString(longScene));
        var batch=new ArrayList<Scene>();for(int i=1;i<=12;i++){var s=scene(T2V,i);dialogue(s,8,200);batch.add(s);}
        var total=assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(batch));
        assertEquals("VIDEO_PROMPT_TOO_LONG",total.code());assertNotNull(total.sceneOrdinal());
    }

    // 【测什么】重复key、空批、超12幕及不支持的指南不能进入自适应分配，避免映射覆盖。
    // 【怎么算红】用Map静默覆盖重复key或允许未知模板进入v4，异常断言红。
    @Test void invalidBatchAndUnsupportedTemplatesCannotAllocate() {
        var s=scene(T2V,1);
        assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(List.of()));
        assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(List.of(s,s)));
        var many=new ArrayList<Scene>();for(int i=1;i<=13;i++)many.add(scene(T2V,i));
        assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(many));
        assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.limits(List.of(copy(s,s.guide()+" custom",s.templateId()))));
    }

    private Scene scene(String model,int ordinal) {
        String key="s"+ordinal;
        var source=json.createObjectNode().put("artifactId","board").put("version",1).put("sceneId",key);
        var parameters=json.createObjectNode().put("model",model).put("duration",8).put("ratio","16:9").put("prompt","Wind moves leaves.");
        var request=json.createObjectNode().put("key",key).put("ordinal",ordinal).put("model",model);
        request.set("parameters",parameters);request.set("source",source);
        request.putObject("storyboardScene").put("sceneId",key).put("visual","Wind moves leaves.").put("narration","").put("duration",8);
        request.putArray("speakerBindings");
        var template=new PromptTemplateService().resolve(new PromptContext(model,0,0,0,8,"16:9"));
        var quote=new TaskQuote("fixture",model,model,"VIDEO",parameters,BigDecimal.ONE,"CNY","AGENT");
        return new Scene(key,ordinal,source,quote,request,template.guide(),template.resourcePath());
    }
    private Scene copy(Scene s,String guide,String templateId) {
        return new Scene(s.key(),s.ordinal(),s.source(),s.quote(),s.request(),guide,templateId);
    }
    private Scene frame(String mode) {
        var s=scene("minimax-h3-fl2va-hd",1);var parameters=(ObjectNode)s.request().path("parameters");
        parameters.putObject("referenceImage").put("artifactId","image").put("version",1);
        parameters.put("referenceMode",mode);
        var template=new PromptTemplateService().resolve(new PromptContext(s.quote().modelId(),1,0,0,8,"16:9"));
        return copy(s,template.guide(),template.resourcePath());
    }
    private void dialogue(Scene s,int count,int chars) {
        var lines=((ObjectNode)s.request().path("storyboardScene")).putObject("sound").putArray("dialogue");
        var bindings=((ObjectNode)s.request()).putArray("speakerBindings");
        for(int i=1;i<=count;i++) {lines.addObject().put("speaker","角色"+i).put("text","话".repeat(chars));bindings.addObject().put("speaker","角色"+i).put("id","S"+i);}
    }
    private ObjectNode sections() {
        return json.createObjectNode().put(THREE.get(0),"[Shot 1] Wind moves leaves.").put(THREE.get(1),"Wind.").put(THREE.get(2),"N/A");
    }
    private String assembled(ObjectNode fields) {
        return THREE.stream().map(h->h+":\n"+fields.path(h).asText()).collect(java.util.stream.Collectors.joining("\n\n"));
    }
    private String wire(String key,ObjectNode sections) throws Exception {
        var root=json.createObjectNode();root.putArray("items").addObject().put("key",key).set("sections",sections);
        return json.writeValueAsString(root);
    }
    private String parse(Scene s,ObjectNode fields,int limit) throws Exception {
        return StructuredVideoPromptProtocol.parse(json,wire(s.key(),fields),s,limit);
    }
    private VideoPreparationException invalid(Scene s,String raw,int limit,String rule) {
        var failure=assertThrows(VideoPreparationException.class,()->StructuredVideoPromptProtocol.parse(json,raw,s,limit));
        assertEquals("VIDEO_PROMPT_OUTPUT_INVALID",failure.code());assertEquals(rule,failure.validationCode());
        assertEquals(s.ordinal(),failure.sceneOrdinal());return failure;
    }
}
