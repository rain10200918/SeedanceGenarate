package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.agent.skill.StoryboardSceneDetails;
import org.example.seedancegenarate.service.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import static org.example.seedancegenarate.agent.generation.VideoPreparationException.ValidationRule.*;
import java.util.*;

/** Pure plan construction and one bounded text invocation per durable scene checkpoint. */
@Component
public class AgentVideoPromptPreparation {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentVideoPromptPreparation.class);
    public record PreparedQuotes(TaskQuote first,AgentBatchRuntime.Prepared batch) {}
    public record Scene(String key,int ordinal,JsonNode source,TaskQuote quote,JsonNode request,String guide,String templateId) {
        public Scene(String key,int ordinal,JsonNode source,TaskQuote quote,JsonNode request,String guide) {
            this(key,ordinal,source,quote,request,guide,"provided:guide");
        }
    }
    public record Plan(String bindingHash,String batchStepId,List<Scene> scenes,int perPrompt) {
        public Plan { scenes=List.copyOf(scenes); }
    }
    private final AgentGenerationGateway gateway;
    private final AgentModelGateway models;
    private final PromptTemplateService templates;
    private final ObjectMapper json;
    private final AgentVideoPromptDiagnostics diagnostics;
    public AgentVideoPromptPreparation(AgentGenerationGateway gateway,AgentModelGateway models,PromptTemplateService templates,ObjectMapper json) {
        this(gateway,models,templates,json,new AgentVideoPromptDiagnostics(json,false,"./.agent-diagnostics"));
    }
    @Autowired
    public AgentVideoPromptPreparation(AgentGenerationGateway gateway,AgentModelGateway models,PromptTemplateService templates,ObjectMapper json,AgentVideoPromptDiagnostics diagnostics) {
        this.gateway=gateway;this.models=models;this.templates=templates;
        this.diagnostics=diagnostics;
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public Plan preparePlan(AgentContext context,TaskQuote first,AgentBatchRuntime.Prepared batch) {
        if(first==null||!"VIDEO".equals(first.mediaType())||!"AGENT".equals(first.origin()))
            throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
        var items=batch==null?List.of(new AgentBatchRuntime.Item("single",singleOrdinal(context),
                context.selection()==null?json.nullNode():json.valueToTree(context.selection()),first)):batch.items();
        if(items.isEmpty()||items.size()>12)throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
        int perPrompt=Math.min(4000,16000/items.size());
        var scenes=new ArrayList<Scene>();
        var keys=new HashSet<String>();
        for(var item:items) {
            var quote=item.quote();var spec=quote.inputSnapshot();
            if(!keys.add(item.sceneId())||!first.modelId().equals(quote.modelId())||!first.provider().equals(quote.provider()))
                throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
            // Keep actual per-scene duration; never silently copy the first scene's duration.
            var entry=json.createObjectNode().put("key",item.sceneId()).put("ordinal",item.ordinal()).put("model",quote.modelId());
            entry.set("parameters",gateway.videoParameters(spec));entry.set("source",item.source());
            JsonNode sourceData=null;
            for(var artifact:context.artifacts())if(artifact.id().equals(item.source().path("artifactId").asText())&&artifact.version()==item.source().path("version").asInt()&&"STORYBOARD".equals(artifact.type()))sourceData=artifact.data();
            var creationSpec=org.example.seedancegenarate.agent.skill.CreationSpecSupport.resolve(context,sourceData);
            if(creationSpec!=null)entry.set("creationSpec",creationSpec);
            if(spec.has("_reference"))entry.put("referenceTitle",spec.path("_reference").path("title").asText());
            if(batch==null&&context.selection()!=null&&Objects.equals(context.selection().sceneId(),item.source().path("sceneId").asText(null)))
                entry.put("plannerDraft",first.inputSnapshot().path("_originalPrompt").asText(first.inputSnapshot().path("prompt").asText()));
            for(var artifact:context.artifacts())if(artifact.id().equals(item.source().path("artifactId").asText())
                    &&artifact.version()==item.source().path("version").asInt()&&"STORYBOARD".equals(artifact.type())&&artifact.data()!=null)
                for(var scene:artifact.data().path("scenes"))if(scene.path("sceneId").equals(item.source().path("sceneId")))
                    entry.set("storyboardScene",scene.deepCopy());
            var speakers=entry.putArray("speakerBindings");
            speakerBindings(entry.path("storyboardScene")).forEach((name,id)->speakers.addObject().put("speaker",name).put("id",id));
            if(entry.toString().length()>24000)throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_LENGTH).atScene(item.ordinal());
            var template=templates.resolve(new PromptContext(quote.modelId(),spec.has("referenceImage")?1:0,0,0,
                    spec.path("duration").asInt(),spec.path("ratio").asText()));
            scenes.add(new Scene(item.sceneId(),item.ordinal(),item.source(),quote,entry,template.guide(),template.resourcePath()));
        }
        var promptContext=preparationContext(context);
        var binding=json.createObjectNode().put("protocol","video-prompt-scenes-v3").put("channel",context.channel())
                .put("modelBinding",context.modelBinding()).put("perPrompt",perPrompt);
        binding.put("goal",promptContext.goal());binding.set("confirmedChoices",json.valueToTree(promptContext.confirmedChoices()));
        binding.set("imageAssetIds",json.valueToTree(context.imageAssetIds()));
        binding.set("scenes",json.valueToTree(scenes));
        // Include the actual policy so any template/protocol/input change invalidates reuse.
        var policies=binding.putArray("policies");scenes.forEach(scene->policies.add(policy(scene.guide(),perPrompt)));
        return new Plan(hash(binding),batch==null?null:batch.stepId(),scenes,perPrompt);
    }
    public String prepareScene(AgentContext context,Plan plan,Scene scene) {
        return prepareScene(context,plan,scene,null);
    }
    public String prepareScene(AgentContext context,Plan plan,Scene scene,String repairHint) {
        if(!plan.scenes().contains(scene))throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
        var request=json.createObjectNode();request.putArray("items").add(scene.request());
        log.info("Agent video prompt template: scene={}, templateJson={}",scene.ordinal(),json.valueToTree(scene.templateId()));
        String trusted=VideoPreparationException.ValidationRule.trustedHint(repairHint);
        String instructions=policy(scene.guide(),plan.perPrompt())+(trusted==null?"":"\n本次仅修正当前幕的文本格式与完整性："+trusted);
        String raw=models.complete(preparationContext(context),"AGENT_VIDEO_PROMPT",instructions,request.toString());
        try {
            String prompt=parse(raw,Set.of(scene.key()),plan.perPrompt(),scene.guide()).get(scene.key());
            validateNarration(scene,prompt);return prompt;
        } catch(VideoPreparationException failure) {
            throw failure.withDiagnosticId(diagnostics.record(context,scene,failure,raw)).atScene(scene.ordinal()).withSource(scene.source());
        }
    }
    private int singleOrdinal(AgentContext context) {
        var source=context.selection();if(source==null||source.sceneId()==null)return 1;
        for(var artifact:context.artifacts())if(artifact.id().equals(source.artifactId())&&artifact.version()==source.version()
                &&"STORYBOARD".equals(artifact.type())&&artifact.data()!=null) {
            int ordinal=0;for(var scene:artifact.data().path("scenes")) {
                ordinal++;if(source.sceneId().equals(scene.path("sceneId").asText()))return ordinal;
            }
        }
        return 1;
    }
    public PreparedQuotes assemble(Plan plan,Map<String,String> prompts) {
        if(prompts.size()!=plan.scenes().size())throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
        var prepared=new ArrayList<AgentBatchRuntime.Item>();int total=0;
        for(var scene:plan.scenes()) {
            String prompt=prompts.get(scene.key());
            var response=json.createObjectNode();response.putArray("items").addObject().put("key",scene.key()).put("prompt",prompt);
            parse(response.toString(),Set.of(scene.key()),plan.perPrompt(),scene.guide());validateNarration(scene,prompt);
            total+=prompt.length();
            prepared.add(new AgentBatchRuntime.Item(scene.key(),scene.ordinal(),scene.source(),gateway.withPreparedVideoPrompt(scene.quote(),prompt)));
        }
        if(total>16000)throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_LENGTH);
        return new PreparedQuotes(prepared.get(0).quote(),plan.batchStepId()==null?null:new AgentBatchRuntime.Prepared(plan.batchStepId(),prepared));
    }
    /** Compatibility entry point; the durable runtime uses prepareScene, never this batch invocation. */
    public PreparedQuotes prepare(AgentContext context,TaskQuote first,AgentBatchRuntime.Prepared batch) {
        Plan plan=preparePlan(context,first,batch);var request=json.createObjectNode();var items=request.putArray("items");
        var keys=new HashSet<String>();plan.scenes().forEach(scene->{items.add(scene.request());keys.add(scene.key());});
        if(request.toString().length()>24000)throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_LENGTH);
        String guide=plan.scenes().get(0).guide();
        return assemble(plan,parse(models.complete(preparationContext(context),"AGENT_VIDEO_PROMPT",policy(guide,plan.perPrompt()),request.toString()),keys,plan.perPrompt(),guide));
    }
    /** Match the gateway's finite goal/choice limits; all scene/reference facts are already in request. */
    private AgentContext preparationContext(AgentContext context) {
        var choices=context.confirmedChoices();
        var boundedChoices=choices.subList(Math.max(0,choices.size()-12),choices.size()).stream().map(choice->clip(choice,1000)).toList();
        return new AgentContext(context.userId(),context.sessionId(),context.turnId(),context.channel(),clip(context.goal(),4000),
                null,List.of(),List.of(),context.step(),boundedChoices,null,null,null,null,context.outputRepair(),
                context.imageAssetIds(),context.modelBinding());
    }
    private String clip(String value,int limit) {return value==null?"":value.substring(0,Math.min(value.length(),limit));}
    private Map<String,String> speakerBindings(JsonNode scene) {
        var bindings=new LinkedHashMap<String,String>();
        for(var line:scene.path("sound").path("dialogue")) {
            String name=line.path("speaker").asText();
            bindings.computeIfAbsent(name,ignored->"S"+(bindings.size()+1));
        }
        return bindings;
    }
    private void validateNarration(Scene scene,String prompt) {
        List<String> spoken;
        try {
            spoken=StoryboardSceneDetails.spokenLines(scene.request().path("storyboardScene"));
        } catch(IllegalArgumentException|org.example.seedancegenarate.exception.BusinessException failure) {
            throw VideoPreparationException.invalid(SOURCE_SPEECH_INVALID,"$.storyboardScene.sound").atScene(scene.ordinal());
        }
        String normalized=prompt.replace('：',':').replaceAll("\\s+","");
        var attributedDialogue=new HashSet<String>();
        int dialogueCount=scene.request().path("storyboardScene").path("sound").path("dialogue").size();
        var bindings=speakerBindings(scene.request().path("storyboardScene"));
        for(var line:scene.request().path("storyboardScene").path("sound").path("dialogue")) {
            String speaker=line.path("speaker").asText().replace('：',':');
            String text=line.path("text").asText().replace('：',':').replaceAll("\\s+","");
            String role="(?<![\\p{L}\\p{N}_])"+java.util.regex.Pattern.quote(speaker);
            String literalText=text.codePoints().mapToObj(c->java.util.regex.Pattern.quote(new String(Character.toChars(c))))
                    .collect(java.util.stream.Collectors.joining("\\s*"));
            if(java.util.regex.Pattern.compile(role+"\\s*:\\s*"+literalText).matcher(prompt.replace('：',':')).find())
                attributedDialogue.add(speaker.replaceAll("\\s+","")+":"+text);
            // IDs come from the source dialogue and are supplied to the writer, never inferred from English prose.
            // Preserve the word boundary before literal names so a prefixed name cannot impersonate a speaker.
            String boundId=java.util.regex.Pattern.quote(bindings.get(line.path("speaker").asText()));
            String subject="(?:"+role+"|(?<![\\p{L}\\p{N}_])The character)";
            String h3=subject+"\\s*\\("+boundId+"\\)\\s*(?:says|shouts)\\s*:\\s*<d>\\s*(?:\\[Chinese\\]\\s*)?([\\s\\S]*?)</d>";
            var matches=java.util.regex.Pattern.compile(h3).matcher(prompt.replace('：',':'));
            while(matches.find())if(text.equals(matches.group(1).replaceAll("\\s+","")))
                attributedDialogue.add(speaker.replaceAll("\\s+","")+":"+text);
        }
        for(int i=0;i<spoken.size();i++) {
            String text=spoken.get(i).replace('：',':').replaceAll("\\s+","");
            boolean found=i<dialogueCount?attributedDialogue.contains(text):normalized.contains(text);
            if(!found)throw VideoPreparationException.invalid(DIALOGUE_MISSING,"$.items[0].prompt.speech["+i+"]").atScene(scene.ordinal());
        }
    }
    private String policy(String guide,int perPrompt) {
        return "为每一幕准备最终可执行视频提示词。下列模型模板仅约束每个items.prompt字段正文：\n"+guide
                +"\n\n响应协议优先于模板的纯文本输出措辞：只返回严格JSON {\"items\":[{\"key\":\"输入原key\",\"prompt\":\"最终正文\"}]}，不加代码围栏、解释或其他字段。"
                +"必须覆盖输入的全部key且各出现一次，不能新增、遗漏或合并幕。每条prompt最多"+perPrompt+"字符，全部正文合计最多16000字符，完整JSON最多24000字符。"
                +"逐幕使用自己的parameters.duration、ratio、visualStyle和storyboardScene，保留narration原文并按模板表达对白/旁白。"
                +"存在characters/shot/sound时分别继承角色身份外观服装、镜头动作景别运镜起止、声音要求；角色referenceImage仅是已知资料，不代表额外媒体已附加，实际参考仅以parameters.referenceImage为准。"
                +"sound.dialogue逐条保留归属和每句原文。speakerBindings由系统按本幕对白首次出现顺序指定角色编号，同一角色始终使用同一编号；该映射优先于模板的自行编号要求，不得交换角色编号。使用H3模板时写成The character (S1) says: <d>[Chinese] 原文台词</d>（喊叫可用shouts），S1必须替换为speakerBindings中该说话人的准确编号。角色介绍也必须沿用该编号，非对白角色不能占用已绑定编号。也可用真实角色名替代The character；角色名不要放进d标签被念出来。其他模板可用说话人:原文台词连续格式，可使用中文冒号。旁白逐行保留，不要求把整段旁白连续粘贴。sound.narration与顶层narration是相同旁白，勿重复。"
                +"参考图只能按实际referenceImage数量与角色使用，不虚构视频或音频参考；沿用已确认角色和风格。"
                +"不修改时长、模型、画幅、参考图片，不把下一幕内容混入当前幕。没有声音要求时按模板填N/A；不得声称已经生成视频。";
    }
    private String hash(JsonNode node) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical(node).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {var sorted=json.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key->sorted.set(key,canonical(node.get(key))));return sorted;}
        if(node.isArray()) {var result=json.createArrayNode();node.forEach(value->result.add(canonical(value)));return result;}
        return node;
    }
    private Map<String,String> parse(String raw,Set<String> expected,int perPrompt,String guide) {
        if(raw==null||raw.isBlank())throw VideoPreparationException.invalid(JSON_INVALID,"$");
        if(raw.length()>24000)throw VideoPreparationException.invalid(PROMPT_TOO_LONG,"$");
        JsonNode root;
        try {
            root=json.readTree(unwrap(raw));
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw VideoPreparationException.invalid(JSON_INVALID,"$");}
            if(root==null||!root.isObject()||root.size()!=1||!root.path("items").isArray())throw VideoPreparationException.invalid(ROOT_SHAPE,"$");
            if(root.path("items").size()!=expected.size())throw VideoPreparationException.invalid(SCENE_KEY_MISMATCH,"$.items");
            var result=new LinkedHashMap<String,String>();int total=0,ordinal=0;
            // Heading names come only from our trusted, packaged model template.
            var headings=guide.lines().map(String::trim).filter(line->line.matches("[a-z_]+:")).distinct().toList();
            for(var item:root.path("items")) {
                String path="$.items["+(ordinal++)+"]";
                if(!item.isObject()||item.size()!=2||!item.path("key").isTextual()||!item.path("prompt").isTextual())throw VideoPreparationException.invalid(ITEM_SHAPE,path);
                String key=item.path("key").asText(),prompt=item.path("prompt").asText().trim();
                if(!expected.contains(key)||result.containsKey(key))throw VideoPreparationException.invalid(SCENE_KEY_MISMATCH,path+".key");
                if(prompt.isBlank())throw VideoPreparationException.invalid(PROMPT_EMPTY,path+".prompt");
                if(prompt.length()>perPrompt)throw VideoPreparationException.invalid(PROMPT_TOO_LONG,path+".prompt");
                validateHeadings(prompt,headings,path+".prompt");
                total+=prompt.length();result.put(key,prompt);
            }
            if(total>16000)throw VideoPreparationException.invalid(PROMPT_TOO_LONG,"$.items");
            return result;
    }
    /** Only remove a single whole-response fence; never extract JSON from prose. */
    private String unwrap(String raw) {
        String value=raw.trim();var lines=value.lines().toList();if(lines.size()<3)return value;
        String first=lines.get(0).trim(),last=lines.get(lines.size()-1).trim();
        String fence=first.equals("```json")||first.equals("```")?"```":first.equals("`json")||first.equals("`")?"`":null;
        return fence!=null&&last.equals(fence)?String.join("\n",lines.subList(1,lines.size()-1)).trim():value;
    }
    private void validateHeadings(String prompt,List<String> headings,String path) {
        var lines=prompt.lines().map(String::trim).toList();var positions=new ArrayList<Integer>();int previous=-1;
        for(int h=0;h<headings.size();h++) {
            String heading=headings.get(h);int found=-1;
            for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith(heading)) {
                if(found!=-1)throw VideoPreparationException.invalid(TEMPLATE_SECTION_MISSING,path+".sections["+h+"]");found=i;
            }
            if(found<=previous)throw VideoPreparationException.invalid(TEMPLATE_SECTION_MISSING,path+".sections["+h+"]");
            positions.add(found);previous=found;
        }
        for(int h=0;h<positions.size();h++) {
            int start=positions.get(h),end=h+1<positions.size()?positions.get(h+1):lines.size();
            boolean content=!lines.get(start).substring(headings.get(h).length()).isBlank();
            for(int i=start+1;i<end&&!content;i++)content=!lines.get(i).isBlank();
            if(!content)throw VideoPreparationException.invalid(TEMPLATE_SECTION_EMPTY,path+".sections["+h+"]");
        }
    }
}
