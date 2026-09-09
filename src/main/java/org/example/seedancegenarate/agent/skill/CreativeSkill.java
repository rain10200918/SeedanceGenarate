package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.agent.model.AgentContext;

/** A capability adapter; no runtime state, wallet, engine routing or arbitrary tool execution. */
public interface CreativeSkill {
    SkillDescriptor descriptor();
    void validate(JsonNode input);
    SkillResult execute(AgentContext context, JsonNode input);
}
