package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.Set;

/** Closed document formats and explicit references shared by the two structured skills. */
public final class StructuredSkillSupport {
    private StructuredSkillSupport() {}
    public static void validatePlanStepScope(JsonNode step,Set<String> priorStoryboards) {
        if(!step.has("scope")&&!step.has("sourceStepId"))return;
        if(!"STORYBOARD_SCENES".equals(step.path("scope").asText())||!Set.of("IMAGE","VIDEO").contains(step.path("kind").asText())
                ||!step.path("sourceStepId").isTextual()||!priorStoryboards.contains(step.path("sourceStepId").asText()))
            throw BusinessException.badRequest("逐幕范围仅适用于媒体步骤，且必须引用本计划前序分镜步骤");
    }
    static ObjectMapper strict(ObjectMapper json) {
        return json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    static JsonNode schema(ObjectMapper json, String sourceDescription) {
        var root = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("instruction");
        var p = root.putObject("properties");
        p.putObject("instruction").put("type", "string").put("minLength", 1).put("maxLength", 4000);
        p.set("source", referenceSchema(json).put("description", sourceDescription));
        return root;
    }
    static JsonNode taskSchema(JsonNode schema) {
        if (schema == null) return null; // Test doubles may omit model discovery.
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) schema.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.get("properties"))
                .set("source", referenceSchema(new ObjectMapper()).put("description", "文本作品/分镜的精确引用，不是媒体参考输入。"));
        return result;
    }
    static JsonNode taskInput(JsonNode input) {
        if (input == null || !input.isObject()) throw invalid();
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) input.deepCopy();
        if (result.has("source")) { reference(result.get("source")); result.remove("source"); }
        return result;
    }
    static com.fasterxml.jackson.databind.node.ObjectNode referenceSchema(ObjectMapper json) {
        var r = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        r.putArray("required").add("artifactId").add("version");
        var p = r.putObject("properties");
        p.putObject("artifactId").put("type", "string").put("minLength", 1).put("maxLength", 64);
        p.putObject("version").put("type", "integer").put("minimum", 1);
        p.putObject("sceneId").put("type", "string").put("minLength", 1).put("maxLength", 64);
        return r;
    }
    static void validateInput(JsonNode input) {
        object(input, Set.of("instruction", "source"));
        text(input, "instruction", 4000);
        if (input.has("source")) reference(input.get("source"));
    }
    static ArtifactRef reference(JsonNode node) {
        object(node, Set.of("artifactId", "version", "sceneId"));
        var version = node.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) throw invalid();
        return new ArtifactRef(text(node, "artifactId", 64), version.intValue(), node.hasNonNull("sceneId") ? text(node, "sceneId", 64) : null);
    }
    static ArtifactRef source(AgentContext context, JsonNode input) {
        return input.has("source") ? reference(input.get("source")) : context.selection();
    }
    static AgentContext.ArtifactContext resolve(AgentContext context, ArtifactRef source) {
        if (source == null) throw invalid();
        return context.artifacts().stream().filter(a -> a.id().equals(source.artifactId()) && a.version() == source.version())
                .findFirst().orElseThrow(() -> BusinessException.badRequest("引用作品版本不可访问，请重新选择"));
    }
    static JsonNode output(ObjectMapper json, String raw) {
        return SkillOutputContract.parse(json, raw, 24000);
    }
    static void object(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw BusinessException.badRequest("必须为对象");
        node.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) throw BusinessException.badRequest("包含契约未允许的字段"); });
    }
    static String text(JsonNode node, String field, int max) {
        var v = node.get(field);
        if (v == null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max)
            throw BusinessException.badRequest("字段 " + field + " 必须为1.." + max + "字符的非空文本");
        return v.textValue();
    }
    static BusinessException invalid() { return BusinessException.badRequest("创作结构或来源参数无效"); }
}
