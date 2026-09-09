package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** A proposal, not execution permission. Only AgentPlanner accepts model output. */
public record AgentDecision(String type, String text, String summary, List<Option> options,
                            String skillId, JsonNode input, @JsonIgnore String rawOutput) {
    public AgentDecision(String type,String text,String summary,List<Option> options,String skillId,JsonNode input) {
        this(type,text,summary,options,skillId,input,null);
    }
    public AgentDecision {
        options = options == null ? List.of() : List.copyOf(options);
        input = input == null ? null : input.deepCopy();
    }
    @Override public String toString() { return "AgentDecision[type="+type+"]"; }
    public record Option(String id, String label) {}
}
