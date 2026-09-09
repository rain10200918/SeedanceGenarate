package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.springframework.stereotype.Component;

@Component
public class PromptOptimizationSkill implements CreativeSkill {
    private final TextSkillSupport text;
    public PromptOptimizationSkill(AgentModelGateway gateway, ObjectMapper json) {
        text = new TextSkillSupport(gateway, json, "PROMPT", "AGENT_PROMPT");
    }
    public SkillDescriptor descriptor() {
        return new SkillDescriptor("prompt-optimization", "1.0.0", "根据当前创作上下文整理或修改可复制的图像/视频提示词；只生成文本，不提交媒体任务。", text.schema(), "PROMPT");
    }
    public void validate(JsonNode input) { text.validate(input); }
    public SkillResult execute(AgentContext context, JsonNode input) {
        return text.execute(context, input, "整理准确的创作提示词，明确主体、环境、动作、风格和镜头；保留用户意图，不声称已经生成图像或视频。");
    }
}
