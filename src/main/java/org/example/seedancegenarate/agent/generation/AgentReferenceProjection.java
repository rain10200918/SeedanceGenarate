package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Public approval view only; never copy the frozen storage identity to the client. */
public final class AgentReferenceProjection {
    private AgentReferenceProjection() {}
    public static void apply(ObjectNode target,JsonNode snapshot) {
        target.remove("referenceImage");
        var ref=snapshot.path("referenceImage");var stored=snapshot.path("_reference");
        if(!ref.isObject()||!stored.isObject())return;
        target.putObject("referenceImage").put("artifactId",ref.path("artifactId").asText())
                .put("version",ref.path("version").asInt()).put("title",stored.path("title").asText())
                .put("mediaPath",stored.path("mediaPath").asText()).put("mode",snapshot.path("referenceMode").asText());
    }
}
