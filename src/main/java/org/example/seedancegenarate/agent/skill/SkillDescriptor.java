package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;

public record SkillDescriptor(String id, String version, String description, JsonNode inputSchema, String resultType) {
    public SkillDescriptor(String id, String version, String description, JsonNode inputSchema) {
        this(id, version, description, inputSchema, null);
    }
}
