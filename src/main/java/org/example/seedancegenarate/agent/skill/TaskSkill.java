package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.agent.model.AgentContext;

/** Long-running capability: quote first, execute only via durable user approval. */
public interface TaskSkill extends CreativeSkill {
    TaskQuote quote(AgentContext context,JsonNode input);
    @Override default SkillResult execute(AgentContext context,JsonNode input) {
        throw new IllegalStateException("此技能必须先经用户费用确认");
    }
}
