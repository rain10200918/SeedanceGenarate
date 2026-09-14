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
    /** Root projection is populated by Runtime from DB, never copied from a plan artifact or LLM input. */
    public JsonNode confirmedRepair(String kind,JsonNode source) {
        if(plan==null)return null;
        String step=null;for(var item:plan.path("steps"))if(item.path("id").equals(plan.path("currentStepId")))step=item.path("executionStepId").asText();
        JsonNode found=null;
        for(var binding:plan.path("_confirmedRepairs")) {
            var target=binding.path("target");var ref=target.path("sourceRef");
            if(!kind.equals(target.path("kind").asText())||!java.util.Objects.equals(step,target.path("executionStepId").asText()))continue;
            if(source==null||source.isNull()) {if(!ref.isMissingNode()&&!ref.isNull())continue;}
            else if(!ref.path("artifactId").equals(source.path("artifactId"))||!ref.path("version").equals(source.path("version"))
                    ||!java.util.Objects.equals(ref.path("sceneId").asText(null),source.path("sceneId").asText(null)))continue;
            found=binding.get("spec");
        }
        return found==null?null:found.deepCopy();
    }
    public JsonNode videoRepairBaseline() {
        if(plan==null)return null;
        for(var step:plan.path("steps"))if(step.path("id").equals(plan.path("currentStepId")))
            for(var binding:plan.path("_confirmedRepairs"))if("VIDEO".equals(binding.path("target").path("kind").asText())
                    &&step.path("executionStepId").equals(binding.path("target").path("executionStepId")))return binding.path("baseline").deepCopy();
        return null;
    }
    public JsonNode confirmedStoryboardRepair(ArtifactRef source) {
        if(plan==null||source==null)return null;
        for(var binding:plan.path("_confirmedRepairs")) {
            var ref=binding.path("resultRef");
            if("STORYBOARD".equals(binding.path("target").path("kind").asText())&&source.artifactId().equals(ref.path("artifactId").asText())&&source.version()==ref.path("version").asInt())
                return binding.path("spec").deepCopy();
        }
        return null;
    }
    public record ArtifactRef(String artifactId, int version, String sceneId) {}
    public record ArtifactContext(String id, int version, String type, String title, String content, JsonNode data) {
        public ArtifactContext { data = data == null ? null : data.deepCopy(); }
        public ArtifactContext(String id, int version, String type, String title, String content) {
            this(id, version, type, title, content, null);
        }
    }
}
