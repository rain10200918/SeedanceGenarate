package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageGenerationSkill implements TaskSkill {
    private final AgentGenerationGateway gateway;
    public SkillDescriptor descriptor() { return new SkillDescriptor("image-generation","1","单张文生图；source只引用文本分镜，不是参考图。先报价，经用户按钮确认后生成。不接受URL或批量。",StructuredSkillSupport.taskSchema(gateway.schema("IMAGE")),"IMAGE"); }
    public void validate(JsonNode input) { gateway.validate("IMAGE",StructuredSkillSupport.taskInput(input)); }
    public TaskQuote quote(AgentContext context,JsonNode input) { return gateway.quote("IMAGE",StructuredSkillSupport.taskInput(input)); }
}
