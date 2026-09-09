package org.example.seedancegenarate.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class AgentViews {
    private AgentViews() {}
    public record Conversation(String id, String title, String updatedAt) {}
    public record ArtifactPage(List<Artifact> items,String nextCursor) {}
    public record Message(String id, long seq, String role, JsonNode parts, String createdAt,String clientMsgId) {
        public Message(String id,long seq,String role,JsonNode parts,String createdAt) { this(id,seq,role,parts,createdAt,null); }
    }
    public record Artifact(String id, int version, String type, String title, String content,String taskId,
                           JsonNode data,JsonNode sourceRef,JsonNode planRef,String stepId) {
        public Artifact(String id,int version,String type,String title,String content,String taskId) { this(id,version,type,title,content,taskId,null,null,null,null); }
        public Artifact(String id,int version,String type,String title,String content) { this(id,version,type,title,content,null); }
    }
    public record State(String goal, String summary,JsonNode workspace,JsonNode recipeRun,JsonNode actionableError,JsonNode preparation) {
        public State(String goal,String summary,JsonNode workspace,JsonNode recipeRun){this(goal,summary,workspace,recipeRun,null,null);}
        public State(String goal,String summary,JsonNode workspace){this(goal,summary,workspace,null);}
        public State(String goal,String summary) { this(goal,summary,null); }
    }
    public record Turn(String id, String status, String channel, String error,String mode) {
        public Turn(String id,String status,String channel,String error) { this(id,status,channel,error,"AGENT"); }
    }
    public record Snapshot(String id, String title, long revision, State state, Turn turn,
                           List<Message> messages, List<Artifact> artifacts) {}
}
