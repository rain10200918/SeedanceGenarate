package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;

/** artifactId null means new work; a non-null id means a new version, never overwrite. */
public record SkillResult(String type, String title, String content, String artifactId, JsonNode data, ArtifactRef source) {
    public SkillResult { data = data == null ? null : data.deepCopy(); }
    public SkillResult(String type, String title, String content, String artifactId) {
        this(type, title, content, artifactId, null, null);
    }
}
