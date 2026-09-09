package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.springframework.stereotype.Component;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.HashSet;
import java.util.Set;
import static org.example.seedancegenarate.agent.skill.StructuredSkillSupport.*;

@Component
public class StoryboardGenerationSkill implements CreativeSkill {
    private final AgentModelGateway gateway;
    private final ObjectMapper json;
    private final StoryboardVideoCapabilities videoCapabilities;
    public StoryboardGenerationSkill(AgentModelGateway gateway, ObjectMapper json) {
        this(gateway,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StoryboardGenerationSkill(AgentModelGateway gateway,ObjectMapper json,StoryboardVideoCapabilities videoCapabilities) {
        this.gateway = gateway; this.json = StructuredSkillSupport.strict(json);this.videoCapabilities=videoCapabilities;
    }
    public SkillDescriptor descriptor() {
        var schema=(ObjectNode)StructuredSkillSupport.schema(json,"创建分镜引用SCRIPT精确版本；修改必须引用STORYBOARD精确版本及sceneId；可沿用selection。");
        var requirements=((ObjectNode)schema.get("properties")).putObject("videoRequirements")
                .put("type","object").put("additionalProperties",false)
                .put("description","仅明确后续生成视频时填写；准确传递用户已选model、每幕duration、ratio、referenceMode；没有指定则省略对应字段，不能猜测或修改用户要求。纯文字创作省略整个对象。");
        var p=requirements.putObject("properties");
        p.putObject("model").put("type","string").put("minLength",1).put("maxLength",128);
        p.putObject("duration").put("type","integer").put("minimum",1).put("maximum",120);
        p.putObject("ratio").put("type","string").put("minLength",1).put("maxLength",16);
        p.putObject("referenceMode").put("type","string").putArray("enum").add("REFERENCE_IMAGE").add("FIRST_FRAME");
        return new SkillDescriptor("storyboard-generation", "1.1.0", "从脚本生成分镜或修改选中一幕；分开描述角色、镜头、声音，视频计划先检查真实模型能力，不会生成媒体。",schema,"STORYBOARD");
    }
    public void validate(JsonNode input) {
        object(input,Set.of("instruction","source","videoRequirements"));text(input,"instruction",4000);
        if(input.has("source"))reference(input.get("source"));
        if(input.has("videoRequirements")) {
            var r=input.get("videoRequirements");object(r,Set.of("model","duration","ratio","referenceMode"));
            if(r.has("model"))text(r,"model",128);
            if(r.has("ratio"))text(r,"ratio",16);
            if(r.has("referenceMode")&&!Set.of("REFERENCE_IMAGE","FIRST_FRAME").contains(text(r,"referenceMode",32)))throw invalid();
            if(r.has("duration")&&(!r.get("duration").isIntegralNumber()||!r.get("duration").canConvertToInt()||r.get("duration").asInt()<1||r.get("duration").asInt()>120))throw invalid();
        }
    }
    public SkillResult execute(AgentContext context, JsonNode input) {
        validate(input);
        var ref = source(context, input);
        var previous = resolve(context, ref);
        boolean edit = "STORYBOARD".equals(previous.type());
        int selectedIndex = -1;
        if (edit) {
            if (ref.sceneId() == null) throw BusinessException.badRequest("请先选择需要修改的分镜场景");
            validateBoard(previous.data());
            for (int i = 0; i < previous.data().path("scenes").size(); i++)
                if (ref.sceneId().equals(previous.data().path("scenes").get(i).path("sceneId").asText())) selectedIndex = i;
            if (selectedIndex < 0) throw BusinessException.badRequest("该版本中没有所选分镜场景");
        } else if (!"SCRIPT".equals(previous.type()) || ref.sceneId() != null) {
            throw BusinessException.badRequest("请先选择脚本版本，再创建分镜");
        }
        var request = json.createObjectNode(); request.set("input", input);
        ObjectNode sourceDocument = json.valueToTree(previous);
        if (edit && previous.data() != null) sourceDocument.remove("content"); // Board scenes replace prose; script citation metadata does not.
        request.set("sourceArtifact", sourceDocument);
        JsonNode target=CreationSpecSupport.resolve(context,previous.data());
        if(target!=null)request.set("creationSpec",target);
        JsonNode capabilities=videoCapabilities==null?null:videoCapabilities.prepare(context,input,previous.data());
        if(capabilities!=null)request.set("videoCapabilities",capabilities);
        String shape = edit ? "只返回JSON {scene:{title,visual,narration,duration?}}，只修改选中sceneId的场景，其他幕不要返回。"
                : "只返回JSON {title,scenes:[{title,visual,narration,duration?}]}，title 1..128字，scenes 1..12幕；不要返回sceneId，系统分配。";
        String raw = gateway.complete(context, "AGENT_STORYBOARD", "依据所引用的精确脚本/分镜版本创作。" + shape
                + "每幕title 1..120字，visual 1..800字，narration 0..400字，duration如有为1..120整数秒。"
                + "每幕可另含characters、shot、sound；不是把所有信息塞进visual。characters最多8项，每项{name(1..80),appearance?(1..400),wardrobe?(1..300),referenceImage?:{artifactId,version}}；参考只能引用上下文真实IMAGE精确版本或计划已确认referenceImage，无可用引用则省略，不编造ID或声称看过图片。"
                + "shot可含action、framing、cameraMovement、startState、endState，各1..400字。sound可含dialogue:[{speaker(1..80),text(1..400)}](最多12条)、narration(0..400)、ambience(1..400)。"
                + "narration只写旁白，人物台词放sound.dialogue；sound.narration若填写必须与顶层narration逐字相同。没有旁白时顶层narration为空串。保持对白归属、角色外观服装及镜头起止连续，不把制作说明写成台词。"
                + "存在videoCapabilities时，每幕必须有duration并符合其离散durations或闭区间durationMin..durationMax；指定duration则每幕严格等于它。遵守referenceImage与imageInputMode的参考角色，不将角色参考换为首帧；不得改变模型、画幅、参考或用户时长。"
                + "存在creationSpec.totalDurationSeconds时，全部场景duration总和必须准确等于目标秒数，不是每幕时长。局部修改后完整分镜总和也必须保持；creationSpec.ratio适用于全片。"
                + "不附加其他字段、不输出任务状态、不生成媒体，不编造机构事实。", request.toString());
        ObjectNode data;
        String title;
        try {
            var result = output(json, raw);
            if (edit) {
                object(result, Set.of("scene"));
                var changed = scene(result.get("scene"), false);
                changed.put("sceneId", ref.sceneId());
                data = previous.data().deepCopy();
                ((ArrayNode) data.get("scenes")).set(selectedIndex, changed);
                title = previous.title();
            } else {
                object(result, Set.of("title", "scenes")); title = text(result, "title", 128);
                var values = result.get("scenes");
                if (values == null || !values.isArray() || values.isEmpty() || values.size() > 12) throw BusinessException.badRequest("字段 scenes 必须为1..12项数组");
                data = json.createObjectNode(); var scenes = data.putArray("scenes");
                for (int i = 0; i < values.size(); i++) scenes.add(scene(values.get(i), false).put("sceneId", "s" + (i + 1)));
            }
            validateBoard(data);
            StoryboardVideoCapabilities.validateTotal(target,data.path("scenes"));
        } catch (BusinessException e) { throw SkillOutputContract.invalid(e); }
            // Ownership/reference and engine capability checks are business policy, not output repair.
            if(edit)StoryboardSceneDetails.validateReferences(data.path("scenes").get(selectedIndex),context);
            else for(var s:data.path("scenes"))StoryboardSceneDetails.validateReferences(s,context);
            if(capabilities!=null) {
                videoCapabilities.validateScenes(capabilities,data.path("scenes"));
                data.set("videoCapabilities",capabilities);
            }
            if(target!=null)data.set("creationSpec",target);
            StringBuilder content = new StringBuilder();
            for (var s : data.path("scenes")) content.append(s.path("title").asText()).append("\n")
                    .append(s.path("visual").asText()).append("\n").append(s.path("narration").asText()).append("\n");
            return new SkillResult("STORYBOARD", title, content.toString(), edit ? ref.artifactId() : null, data, ref);
    }
    private static ObjectNode scene(JsonNode value, boolean stored) {
        object(value, stored ? Set.of("sceneId", "title", "visual", "narration", "duration","characters","shot","sound") : Set.of("title", "visual", "narration", "duration","characters","shot","sound"));
        text(value, "title", 120); text(value, "visual", 800);
        var narration = value.get("narration");
        if (narration == null || !narration.isTextual() || narration.asText().length() > 400)
            throw BusinessException.badRequest("字段 narration 必须为0..400字符文本");
        if (value.has("duration")) {
            var d = value.get("duration");
            if (!d.isIntegralNumber() || !d.canConvertToInt() || d.intValue() < 1 || d.intValue() > 120)
                throw BusinessException.badRequest("字段 duration 必须为1..120整数");
        }
        if (stored) text(value, "sceneId", 64);
        StoryboardSceneDetails.validate(value);
        return value.deepCopy();
    }
    private static void validateBoard(JsonNode data) {
        object(data, Set.of("scenes","videoCapabilities","creationSpec")); var values = data.get("scenes");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 12) throw BusinessException.badRequest("字段 scenes 必须为1..12项数组");
        Set<String> ids = new HashSet<>();
        for (var s : values) { scene(s, true); if (!ids.add(s.path("sceneId").asText())) throw BusinessException.badRequest("字段 sceneId 不能重复"); }
    }
}
