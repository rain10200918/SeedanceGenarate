package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.config.AgentRuntimeProperties;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import static org.example.seedancegenarate.agent.skill.StructuredSkillSupport.*;

@Component
public class PlanGenerationSkill implements CreativeSkill {
    public static final int MAX_DOCUMENT_CHARS = 65536;
    private final AgentModelGateway gateway;
    private final ObjectMapper json;
    private final AgentRuntimeProperties limits;
    private SearchProvider search;
    @Autowired(required=false)
    void searchProvider(SearchProvider search) {this.search=search;}
    public PlanGenerationSkill(AgentModelGateway gateway, ObjectMapper json) {
        this(gateway,json,new AgentRuntimeProperties());
    }
    @Autowired
    public PlanGenerationSkill(AgentModelGateway gateway,ObjectMapper json,AgentRuntimeProperties limits) {
        this.gateway=gateway; this.json=StructuredSkillSupport.strict(json); this.limits=limits;
    }
    public SkillDescriptor descriptor() {
        var schema=(com.fasterxml.jackson.databind.node.ObjectNode)StructuredSkillSupport.schema(json, "修订时引用PLAN的精确版本，不能选择场景；新计划省略。");
        var properties=(com.fasterxml.jackson.databind.node.ObjectNode)schema.get("properties");
        var image=referenceSchema(json).put("description","用户要求沿用现有图片角色时，绑定IMAGE作品准确id/version；不是PLAN修订source，不接受URL或sceneId。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)image.get("properties")).remove("sceneId");
        properties.set("referenceImage",image);
        properties.set("creationSpec",CreationSpecSupport.schema(json));
        properties.putObject("visualStyle").put("type","string").put("minLength",1).put("maxLength",500)
                .put("description","整片共用的已确定画风与角色描述，不要编造未观察到的图片特征。");
        return new SkillDescriptor("plan-generation", "1.1.0", "提出或修订创作计划草案；可锁定图片角色参考；不是已确认计划，不执行生成。",schema, "PLAN");
    }
    public void validate(JsonNode input) {
        object(input,Set.of("instruction","source","referenceImage","visualStyle","creationSpec"));text(input,"instruction",4000);
        CreationSpecSupport.validate(input.get("creationSpec"));
        if(input.has("source"))reference(input.get("source"));
        if(input.has("referenceImage")){object(input.get("referenceImage"),Set.of("artifactId","version"));reference(input.get("referenceImage"));}
        if(input.has("visualStyle"))text(input,"visualStyle",500);
    }
    public SkillResult execute(AgentContext context, JsonNode input) {
        validate(input);
        var ref = input.has("source") ? reference(input.get("source")) : null;
        // A selected script/scene is creative context, not a PLAN revision instruction.
        if (ref == null && context.selection() != null) {
            var selected = resolve(context, context.selection());
            if ("PLAN".equals(selected.type())) ref = context.selection();
        }
        var request = json.createObjectNode(); request.set("input", input);
        JsonNode previousData=null;
        if (ref != null) {
            var previous = resolve(context, ref);
            if (!"PLAN".equals(previous.type()) || ref.sceneId() != null) throw invalid();
            request.set("sourceArtifact", json.valueToTree(previous));
            previousData=previous.data();
        }
        JsonNode image=input.has("referenceImage")?input.get("referenceImage"):previousData==null?null:previousData.get("referenceImage");
        String visualStyle=input.has("visualStyle")?text(input,"visualStyle",500):previousData==null?null:previousData.path("visualStyle").asText(null);
        JsonNode creationSpec=CreationSpecSupport.merge(previousData==null?null:previousData.get("creationSpec"),input.get("creationSpec"));
        if(creationSpec!=null)request.set("creationSpec",creationSpec);
        if(image!=null&&!image.isNull()) {
            var imageRef=reference(image);
            if(imageRef.sceneId()!=null||!"IMAGE".equals(resolve(context,imageRef).type()))throw BusinessException.badRequest("角色参考必须是可访问的图片作品准确版本");
            request.set("referenceImage",image);
        }
        if(visualStyle!=null)request.put("visualStyle",visualStyle);
        String raw = gateway.complete(context, "AGENT_CREATIVE_PLAN",
                "提出可调整的创作计划，不执行步骤。仅返回JSON {title,goal,constraints:[建议约束],steps:[{id,kind,title}]}。"
                + "title最多128字，goal最多4000字；constraints最多12条、每条最多500字；steps 1.."+limits.getMaxPlanSteps()+"项，"
                + "id为1..32位字母数字下划线/短横线且唯一，kind只可"+(search!=null&&search.available()?"WEB_RESEARCH/":"")+"SCRIPT/PROMPT/STORYBOARD/IMAGE/VIDEO，title最多120字。需要公开事实且搜索能力可用时先WEB_RESEARCH再SCRIPT；未配置搜索时不提议研究执行，先请用户提供资料。"
                + "referenceImage是绑定的角色图片身份，后续VIDEO需要支持REFERENCE_IMAGE的模型；不要仅写文字保证一致性，不把首帧当角色参考。visualStyle是全片共用约束。"
                + "creationSpec是整片目标规格，不是每幕时长；goal、constraints与步骤必须遵守其明确值，不自行更改。缺少的规格为未指定，不编造。规格由系统保存，不在输出中重复creationSpec。"
                + "整片任务的IMAGE/VIDEO步骤应添加scope=STORYBOARD_SCENES及sourceStepId=本计划前序STORYBOARD步骤id，代表逐幕生成并按实际整批报价确认费用。"
                + "步骤示例：[{\"id\":\"b\",\"kind\":\"STORYBOARD\",\"title\":\"分镜\"},{\"id\":\"v\",\"kind\":\"VIDEO\",\"title\":\"逐幕视频\",\"scope\":\"STORYBOARD_SCENES\",\"sourceStepId\":\"b\"}]。"
                + "仅一幕/单张请求省略这两个字段。不得输出confirmed/status/完成度；不包含合成/音乐。",
                request.toString());
        try {
            var result = planOutput(raw);
            object(result, Set.of("title", "goal", "constraints", "steps"));
            String title = text(result, "title", 128), goal = text(result, "goal", 4000);
            var constraints = result.get("constraints");
            if (constraints == null || !constraints.isArray() || constraints.size() > 12) throw BusinessException.badRequest("字段 constraints 必须为最多12项数组");
            for (var c : constraints) if (!c.isTextual() || c.asText().isBlank() || c.asText().length() > 500) throw BusinessException.badRequest("字段 constraints[] 必须为1..500字符文本");
            var steps = result.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > limits.getMaxPlanSteps()) throw BusinessException.badRequest("字段 steps 必须为1.."+limits.getMaxPlanSteps()+"项数组");
            Set<String> ids = new HashSet<>();
            Set<String> storyboardIds=new HashSet<>();
            StringBuilder content = new StringBuilder(goal);
            String visibleSpec="目标规格（采用本计划后生效）：总时长 "
                    +(creationSpec!=null&&creationSpec.hasNonNull("totalDurationSeconds")?creationSpec.path("totalDurationSeconds").asText()+"秒":"未指定")
                    +"；画幅 "+(creationSpec!=null&&creationSpec.hasNonNull("ratio")?creationSpec.path("ratio").asText():"未指定");
            content.append("\n").append(visibleSpec);
            for (var step : steps) {
                object(step, Set.of("id", "kind", "title","scope","sourceStepId"));
                String id = text(step, "id", 32);
                if("WEB_RESEARCH".equals(step.path("kind").asText())&&(search==null||!search.available()))throw BusinessException.badRequest("字段 steps.kind 不能使用本轮未开放的WEB_RESEARCH");
                if (!id.matches("[A-Za-z0-9_-]+") || !ids.add(id.toLowerCase(java.util.Locale.ROOT))
                        || !Set.of("SCRIPT", "PROMPT", "STORYBOARD", "IMAGE", "VIDEO","WEB_RESEARCH").contains(text(step, "kind", 32))) throw BusinessException.badRequest("字段 steps.id 必须为唯一字母数字下划线短横线，kind必须属于本轮允许能力");
                content.append("\n").append(text(step, "title", 120));
                validatePlanStepScope(step,storyboardIds);
                if("STORYBOARD".equals(step.path("kind").asText()))storyboardIds.add(id);
            }
            var data = json.createObjectNode().put("goal", goal);
            var visibleConstraints=json.createArrayNode().add(visibleSpec);constraints.forEach(visibleConstraints::add);
            data.set("constraints", visibleConstraints); data.set("steps", steps);
            if(image!=null&&!image.isNull())data.set("referenceImage",image.deepCopy());
            if(visualStyle!=null)data.put("visualStyle",visualStyle);
            if(creationSpec!=null)data.set("creationSpec",creationSpec.deepCopy());
            return new SkillResult("PLAN", title, content.toString(), ref == null ? null : ref.artifactId(), data, ref);
        } catch (BusinessException e) { throw SkillOutputContract.invalid(e); }
    }
    private JsonNode planOutput(String raw) {
        return SkillOutputContract.parse(json,raw,MAX_DOCUMENT_CHARS);
    }
}
