package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Trusted runtime state; artifact entries are already resolved in the user's scope. */
public record AgentContext(Long userId, String sessionId, String turnId, String channel,
                           String goal, String summary, List<HistoryMessage> messages,
                           List<ArtifactContext> artifacts, int step, List<String> confirmedChoices,
                           JsonNode plan, ArtifactRef selection,JsonNode observations,JsonNode recipe,boolean outputRepair,
                           List<String> imageAssetIds,String modelBinding) {
    public AgentContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        confirmedChoices = confirmedChoices == null ? List.of() : List.copyOf(confirmedChoices);
        plan = plan == null ? null : plan.deepCopy();
        observations = observations == null ? null : observations.deepCopy();
        recipe = recipe == null ? null : recipe.deepCopy();
        imageAssetIds = imageAssetIds == null ? List.of() : List.copyOf(imageAssetIds);
    }
    public AgentContext(Long userId,String sessionId,String turnId,String channel,String goal,String summary,
                        List<HistoryMessage> messages,List<ArtifactContext> artifacts,int step,List<String> choices,
                        JsonNode plan,ArtifactRef selection,JsonNode observations,JsonNode recipe,boolean outputRepair,List<String> imageAssetIds) {
        this(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,choices,plan,selection,observations,recipe,outputRepair,imageAssetIds,null);
    }
    public AgentContext withModelBinding(String binding) {
        return new AgentContext(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,
                confirmedChoices,plan,selection,observations,recipe,outputRepair,imageAssetIds,binding);
    }
    public AgentContext(Long userId,String sessionId,String turnId,String channel,String goal,String summary,
                        List<HistoryMessage> messages,List<ArtifactContext> artifacts,int step,List<String> choices,
                        JsonNode plan,ArtifactRef selection,JsonNode observations,JsonNode recipe,boolean outputRepair) {
        this(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,choices,plan,selection,observations,recipe,outputRepair,List.of());
    }
    public AgentContext withImageAssetIds(List<String> ids) {
        return new AgentContext(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,
                confirmedChoices,plan,selection,observations,recipe,outputRepair,ids,modelBinding);
    }
    public AgentContext(Long userId,String sessionId,String turnId,String channel,String goal,String summary,
                        List<HistoryMessage> messages,List<ArtifactContext> artifacts,int step,List<String> choices,
                        JsonNode plan,ArtifactRef selection,JsonNode observations,JsonNode recipe) {
        this(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,choices,plan,selection,observations,recipe,false);
    }
    /** Only the runtime projects this flag from durable recovery state; request JSON is never bound to it. */
    public AgentContext withOutputRepair(boolean repair) {
        return new AgentContext(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,
                confirmedChoices,plan,selection,observations,recipe,repair,imageAssetIds,modelBinding);
    }
    public AgentContext(Long userId,String sessionId,String turnId,String channel,String goal,String summary,
                        List<HistoryMessage> messages,List<ArtifactContext> artifacts,int step,List<String> choices,
                        JsonNode plan,ArtifactRef selection,JsonNode observations) {
        this(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,choices,plan,selection,observations,null);
    }
    public AgentContext(Long userId,String sessionId,String turnId,String channel,String goal,String summary,
                        List<HistoryMessage> messages,List<ArtifactContext> artifacts,int step,List<String> confirmedChoices,
                        JsonNode plan,ArtifactRef selection) {
        this(userId,sessionId,turnId,channel,goal,summary,messages,artifacts,step,confirmedChoices,plan,selection,null);
    }
    public AgentContext(Long userId, String sessionId, String turnId, String channel, String goal,
                        String summary, List<HistoryMessage> messages, List<ArtifactContext> artifacts, int step,
                        List<String> confirmedChoices) {
        this(userId, sessionId, turnId, channel, goal, summary, messages, artifacts, step, confirmedChoices, null, null);
    }
    public AgentContext(Long userId, String sessionId, String turnId, String channel, String goal,
                        String summary, List<HistoryMessage> messages, List<ArtifactContext> artifacts, int step) {
        this(userId, sessionId, turnId, channel, goal, summary, messages, artifacts, step, List.of());
    }
    public record HistoryMessage(String role, String text) {}
    public record ArtifactRef(String artifactId, int version, String sceneId) {}
    public record ArtifactContext(String id, int version, String type, String title, String content, JsonNode data) {
        public ArtifactContext { data = data == null ? null : data.deepCopy(); }
        public ArtifactContext(String id, int version, String type, String title, String content) {
            this(id, version, type, title, content, null);
        }
    }
}
