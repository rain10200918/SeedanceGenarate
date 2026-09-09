package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.springframework.stereotype.Component;

@Component
public class ScriptGenerationSkill implements CreativeSkill {
    private final TextSkillSupport text;
    public ScriptGenerationSkill(AgentModelGateway gateway, ObjectMapper json) {
        text = new TextSkillSupport(gateway, json, "SCRIPT", "AGENT_SCRIPT");
    }
    public SkillDescriptor descriptor() {
        return new SkillDescriptor("script-generation", "1.0.0", "根据已确认的需求创建或修改宣传片、短片脚本；只生成文本，不生成媒体。", text.schema(), "SCRIPT");
    }
    public void validate(JsonNode input) { text.validate(input); }
    public SkillResult execute(AgentContext context, JsonNode input) {
        return text.execute(context, input, "创作可拍摄的中文脚本，包含结构、场景、画面和旁白；尊重用户确认的风格与约束，不编造机构事实。");
    }
}
