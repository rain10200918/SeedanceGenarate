package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.example.seedancegenarate.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Prompt projection only: never changes persisted execution state or truncates executable JSON. */
final class AgentContextBudget {
    private final ObjectMapper json;
    private final AgentModelCallConfig config;
    AgentContextBudget(ObjectMapper json,AgentModelCallConfig config) {this.json=json;this.config=config;}
    record Result(String content,int estimatedTokens,Map<String,Integer> sections,Integer contextWindow,int outputReserve) {}

    Result build(AgentContext c,String request,String system,JsonNode schema,int outputReserve) {
        try {
            Integer window=config.getContextWindows().get(c.channel());
            int fixed=estimate(system)+estimate(schema==null?"":schema.toString())+config.getSafetyTokens()
                    +c.imageAssetIds().size()*config.getImageTokenReserve();
            long available=config.getMaxInputTokens()-fixed;
            if(window!=null)available=Math.min(available,(long)window-outputReserve-fixed);
            final long limit=available;
            ObjectNode root=json.createObjectNode();
            root.set("request",json.readTree(request));
            root.put("goal",c.goal()==null?"":c.goal());
            root.put("workingSummary",clip(c.summary(),3000));
            root.set("creationPlan",projectPlan(c.plan()));
            root.set("selection",json.valueToTree(c.selection()));
            root.set("creativeRecipe",json.valueToTree(c.recipe()));
            root.set("confirmedChoices",json.valueToTree(c.confirmedChoices()));
            root.put("step",c.step()).put("artifactContentsAreExcerpts",true);
            root.put("contextIsProjection",true);
            if(!c.imageAssetIds().isEmpty())root.set("inputImageAssetIds",json.valueToTree(c.imageAssetIds()));
            // Latest user intent and latest correction are mandatory; older history is supplementary.
            var history=root.putArray("recentMessages");
            int latestUser=-1;
            for(int i=c.messages().size()-1;i>=0;i--)if("USER".equals(c.messages().get(i).role())){latestUser=i;break;}
            if(latestUser>=0)history.add(message(c.messages().get(latestUser)));
            var observations=root.putArray("observations");
            var artifacts=root.putArray("artifacts");
            if(c.observations()!=null && c.observations().isArray() && !c.observations().isEmpty())
                observations.add(c.observations().get(0)); // Store orders decision_seq DESC.
            if(!fits(root,limit))throw BusinessException.badRequest("当前必要上下文与输出预留超出配置预算，请检查上下文窗口配置");
            var relevant=new LinkedHashSet<String>();
            collectReferences(root.path("request"),relevant);collectReferences(root.path("creationPlan"),relevant);
            collectReferences(root.path("creativeRecipe"),relevant);collectReferences(root.path("selection"),relevant);
            var sorted=new ArrayList<>(c.artifacts());
            sorted.sort(Comparator.comparingInt(a->relevant.contains(a.id()+":"+a.version())?0:1));
            for(var a:sorted.stream().limit(20).toList()) {
                var item=json.createObjectNode().put("id",a.id()).put("version",a.version()).put("type",a.type())
                        .put("title",clip(a.title(),128)).put("contentExcerpt",clip(a.content(),relevant.contains(a.id()+":"+a.version())?1600:400));
                artifacts.add(item);
                if(!fits(root,limit)){artifacts.remove(artifacts.size()-1);break;}
            }
            var selectedHistory=new TreeMap<Integer,ObjectNode>();
            if(latestUser>=0)selectedHistory.put(latestUser,message(c.messages().get(latestUser)));
            for(int i=c.messages().size()-1;i>=Math.max(0,c.messages().size()-12);i--) {
                if(i==latestUser)continue;
                selectedHistory.put(i,message(c.messages().get(i)));
                history.removeAll();selectedHistory.values().forEach(history::add);
                if(!fits(root,limit)){selectedHistory.remove(i);history.removeAll();selectedHistory.values().forEach(history::add);break;}
            }
            var sections=new LinkedHashMap<String,Integer>();
            root.fields().forEachRemaining(e->sections.put(e.getKey(),estimate(e.getValue().toString())));
            sections.put("system",estimate(system));sections.put("schema",estimate(schema==null?"":schema.toString()));
            sections.put("imageReserve",c.imageAssetIds().size()*config.getImageTokenReserve());
            return new Result(root.toString(),estimate(root.toString())+fixed,Map.copyOf(sections),window,outputReserve);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw BusinessException.badRequest("Agent 请求结构无效");
        }
    }

    private boolean fits(JsonNode root,long limit) {return root.toString().length()<=32000 && estimate(root.toString())<=limit;}
    private ObjectNode message(AgentContext.HistoryMessage m) {
        return json.createObjectNode().put("role","USER".equals(m.role())?"USER":"ASSISTANT").put("text",clip(m.text(),4000));
    }
    private JsonNode projectPlan(JsonNode plan) {
        if(plan==null || !plan.isObject())return plan;
        ObjectNode copy=plan.deepCopy();
        String current=plan.path("currentStepId").asText();
        var keep=new HashSet<String>();keep.add(current);
        for(var step:plan.path("steps"))if(current.equals(step.path("id").asText())) {
            step.path("dependsOn").forEach(v->keep.add(v.asText()));
            if(step.hasNonNull("sourceStepId"))keep.add(step.path("sourceStepId").asText());
        }
        // Remove only duplicated plan-step definitions; goal, constraints and reference bindings stay intact.
        if(plan.path("confirmed").asBoolean() && !plan.path("steps").isEmpty() && copy.path("data").isObject())
            ((ObjectNode)copy.get("data")).remove("steps");
        var steps=copy.putArray("steps");
        for(var step:plan.path("steps")) {
            if(keep.contains(step.path("id").asText()))steps.add(step);
            else {
                var brief=steps.addObject();
                for(String key:List.of("id","kind","title","status","skillId","artifactRef","dependsOn"))
                    if(step.has(key))brief.set(key,step.get(key));
            }
        }
        return copy;
    }
    private static void collectReferences(JsonNode node,Set<String> refs) {
        if(node.isObject()&&node.hasNonNull("artifactId")&&node.hasNonNull("version"))
            refs.add(node.path("artifactId").asText()+":"+node.path("version").asInt());
        if(node.isContainerNode())node.forEach(child->collectReferences(child,refs));
    }
    // Deliberately labelled estimate: providers tokenize differently, especially images and JSON schemas.
    static int estimate(String text) {return text==null?0:(int)(((long)text.getBytes(StandardCharsets.UTF_8).length+2)/3);}
    private static String clip(String text,int max) {return text==null?"":text.substring(0,Math.min(text.length(),max));}
}
