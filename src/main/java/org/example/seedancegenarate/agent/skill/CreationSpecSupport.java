package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.List;
import java.util.Set;

/** Target specification, not an engine quote. Confirmation belongs to the existing exact Plan version. */
public final class CreationSpecSupport {
    private static final List<String> FIELDS=List.of("totalDurationSeconds","ratio");
    private CreationSpecSupport() {}
    public static ObjectNode schema(ObjectMapper json) {
        var root=json.createObjectNode().put("type","object").put("additionalProperties",false)
                .put("description","用户明确的整片目标规格，非每幕时长；未知字段省略，不猜默认值。用户采用本计划后才确认。");
        var p=root.putObject("properties");
        p.putObject("totalDurationSeconds").put("type","integer").put("minimum",1).put("maximum",1440);
        p.putObject("ratio").put("type","string").put("maxLength",9).put("pattern","^[1-9][0-9]{0,3}:[1-9][0-9]{0,3}$");
        return root;
    }
    public static void validate(JsonNode spec) {
        if(spec==null||spec.isNull())return;
        StructuredSkillSupport.object(spec,Set.copyOf(FIELDS));
        var duration=spec.get("totalDurationSeconds");
        if(duration!=null&&!duration.isNull()&&(!duration.isIntegralNumber()||!duration.canConvertToInt()||duration.asInt()<1||duration.asInt()>1440))
            throw BusinessException.badRequest("creationSpec.totalDurationSeconds 必须为1..1440整数秒");
        var ratio=spec.get("ratio");
        if(ratio!=null&&!ratio.isNull()&&(!ratio.isTextual()||!ratio.asText().matches("[1-9][0-9]{0,3}:[1-9][0-9]{0,3}")))
            throw BusinessException.badRequest("creationSpec.ratio 必须为规范正整数比例，例如16:9");
    }
    /** Revision uses only explicit non-null fields; omitted fields retain the exact previous version. */
    public static JsonNode merge(JsonNode previous,JsonNode supplied) {
        validate(previous);validate(supplied);
        var result=JsonNodeFactory.instance.objectNode();
        for(var spec:new JsonNode[]{previous,supplied})if(spec!=null)for(String field:FIELDS)
            if(spec.hasNonNull(field))result.set(field,spec.get(field).deepCopy());
        return result.isEmpty()?null:result;
    }
    /** sourceData is server-created SCRIPT/STORYBOARD metadata, never arbitrary model output or an unadopted PLAN. */
    public static JsonNode resolve(AgentContext context,JsonNode sourceData) {
        var plan=context.plan();
        JsonNode confirmed=plan!=null&&plan.path("confirmed").asBoolean()?plan.path("data").get("creationSpec"):null;
        JsonNode source=sourceData==null?null:sourceData.get("creationSpec");
        validate(confirmed);validate(source);
        if(confirmed!=null&&source!=null)for(String field:FIELDS)
            if(confirmed.hasNonNull(field)&&source.hasNonNull(field)&&!confirmed.get(field).equals(source.get(field)))
                throw BusinessException.badRequest("作品规格与已确认计划冲突，请先调整并确认计划或选择匹配版本");
        return merge(source,confirmed);
    }
}
