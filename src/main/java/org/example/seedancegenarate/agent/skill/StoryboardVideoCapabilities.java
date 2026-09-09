package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.stereotype.Component;
import java.util.*;

/** Read-only preparation: model capabilities remain authoritative again at quotation. */
@Component
public class StoryboardVideoCapabilities {
    private final AgentGenerationGateway gateway;
    private final VideoEngineRegistry engines;
    private final ModelAccessService access;
    private final ObjectMapper json;
    private final org.example.seedancegenarate.config.AgentRuntimeProperties limits;
    public StoryboardVideoCapabilities(AgentGenerationGateway gateway,VideoEngineRegistry engines,ModelAccessService access,ObjectMapper json) {
        this(gateway,engines,access,json,new org.example.seedancegenarate.config.AgentRuntimeProperties());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public StoryboardVideoCapabilities(AgentGenerationGateway gateway,VideoEngineRegistry engines,ModelAccessService access,ObjectMapper json,org.example.seedancegenarate.config.AgentRuntimeProperties limits) {
        this.gateway=gateway;this.engines=engines;this.access=access;this.json=json;this.limits=limits;
    }
    public JsonNode prepare(AgentContext context,JsonNode input,JsonNode previous) {
        JsonNode stored=previous==null?null:previous.get("videoCapabilities");
        JsonNode requested=input.get("videoRequirements");
        if(stored==null && requested==null && !hasFutureVideo(context.plan()))return null;
        var requirements=json.createObjectNode();
        JsonNode target=CreationSpecSupport.resolve(context,previous);
        if(stored!=null)for(String field:List.of("model","ratio","duration","referenceMode","referenceImage"))
            if(stored.hasNonNull(field))requirements.set(field,stored.get(field));
        if(requested!=null)requested.fields().forEachRemaining(e->{
            if(requirements.has(e.getKey())&&!requirements.get(e.getKey()).equals(e.getValue()))
                throw BusinessException.conflict("分镜已绑定视频规格，请创建新方案后再调整模型、时长或参考角色");
            requirements.set(e.getKey(),e.getValue());
        });
        if(target!=null&&target.hasNonNull("ratio")) {
            if(requirements.has("ratio")&&!requirements.get("ratio").equals(target.get("ratio")))
                throw BusinessException.conflict("分镜画幅与已确认创作规格冲突，请调整方案后确认");
            requirements.set("ratio",target.get("ratio"));
        }
        JsonNode plan=context.plan();
        if(plan!=null&&plan.path("confirmed").asBoolean()&&plan.path("data").hasNonNull("referenceImage")) {
            if(requirements.has("referenceImage")&&!requirements.get("referenceImage").equals(plan.path("data").get("referenceImage")))
                throw BusinessException.conflict("分镜角色参考与已采用计划不一致，请先重新确认创作方案");
            requirements.set("referenceImage",plan.path("data").get("referenceImage"));
            if(requirements.has("referenceMode")&&!"REFERENCE_IMAGE".equals(requirements.path("referenceMode").asText()))
                throw new VideoPreparationException(VideoPreparationException.Reason.REFERENCE);
            requirements.put("referenceMode","REFERENCE_IMAGE");
        }
        if(requirements.has("referenceMode")&&!requirements.has("referenceImage"))
            throw new VideoPreparationException(VideoPreparationException.Reason.REFERENCE);
        var candidates=engines.all().stream().flatMap(e->e.models().stream())
                .filter(m->m.outputType()==OutputType.VIDEO&&access.isOpen(m.model()))
                .filter(m->!requirements.has("model")||m.model().equals(requirements.path("model").asText()))
                .sorted(Comparator.comparingInt((ModelSpec m)->{
                    int index=limits.getVideoModelPriority().indexOf(m.model());return index<0?Integer.MAX_VALUE:index;
                }).thenComparing(ModelSpec::model)).toList();
        BusinessException failure=new VideoPreparationException(VideoPreparationException.Reason.MODEL);
        for(var model:candidates) {
            if(!requirements.has("referenceImage")&&(model.needImages()||model.imageMin()>0||model.needImageOrVideo()))continue;
            var params=requirements.deepCopy().put("model",model.model()).put("prompt","分镜能力检查");
            try { gateway.validate("VIDEO",params); }
            catch(BusinessException e) { if(!Integer.valueOf(400).equals(e.getCode()))throw e; failure=e;continue; }
            if(target!=null&&target.hasNonNull("totalDurationSeconds")&&!reachable(model,requirements,target.path("totalDurationSeconds").asInt())) {
                failure=new VideoPreparationException(VideoPreparationException.Reason.TOTAL_DURATION);continue;
            }
            var result=requirements.deepCopy().put("model",model.model());
            result.put("ratio",requirements.has("ratio")?requirements.path("ratio").asText():model.ratios().get(0));
            result.put("imageInputMode",model.imageInputMode()==null?"UNSPECIFIED":model.imageInputMode().name());
            result.put("durationMin",model.durationMin()).put("durationMax",model.durationMax());
            result.set("durations",json.valueToTree(model.durations()==null?List.of():model.durations()));
            return result;
        }
        throw failure;
    }
    public void validateScenes(JsonNode capabilities,JsonNode scenes) {
        if(capabilities==null)return;
        int ordinal=0;
        for(var scene:scenes) {
            ordinal++;
            if(!scene.hasNonNull("duration"))throw new VideoPreparationException(VideoPreparationException.Reason.SCENE).atScene(ordinal);
            if(capabilities.has("duration")&&!capabilities.get("duration").equals(scene.get("duration")))
                throw new VideoPreparationException(VideoPreparationException.Reason.DURATION).atScene(ordinal);
            var params=json.createObjectNode().put("prompt","分镜能力检查");
            for(String field:List.of("model","ratio","referenceImage","referenceMode"))
                if(capabilities.hasNonNull(field))params.set(field,capabilities.get(field));
            params.set("duration",scene.get("duration"));
            try { gateway.validate("VIDEO",params); }
            catch(VideoPreparationException e) { throw e.atScene(ordinal); }
        }
    }
    /** Validates the full storyboard, including successful scenes reused by a later batch. */
    public static void validateTotal(JsonNode target,JsonNode scenes) {
        CreationSpecSupport.validate(target);
        if(target==null||!target.hasNonNull("totalDurationSeconds"))return;
        int sum=0;
        if(!scenes.isArray()||scenes.isEmpty()||scenes.size()>12)throw new VideoPreparationException(VideoPreparationException.Reason.SCENE);
        for(var scene:scenes) {
            var d=scene.path("duration");
            if(!d.isIntegralNumber()||!d.canConvertToInt()||d.asInt()<1||d.asInt()>120)throw new VideoPreparationException(VideoPreparationException.Reason.SCENE);
            sum+=d.asInt();
        }
        if(sum!=target.path("totalDurationSeconds").asInt())throw new VideoPreparationException(VideoPreparationException.Reason.TOTAL_DURATION);
    }
    private boolean reachable(ModelSpec model,JsonNode requirements,int total) {
        boolean[] previous=new boolean[total+1];previous[0]=true;
        for(int count=1;count<=12;count++) {
            boolean[] next=new boolean[total+1];
            for(int d=1;d<=120;d++) {
                if(requirements.has("duration")&&d!=requirements.path("duration").asInt())continue;
                if(model.durations()!=null&&!model.durations().isEmpty()?!model.durations().contains(d):d<model.durationMin()||d>model.durationMax())continue;
                for(int sum=d;sum<=total;sum++)if(previous[sum-d])next[sum]=true;
            }
            if(next[total])return true;previous=next;
        }
        return false;
    }
    private static boolean hasFutureVideo(JsonNode plan) {
        if(plan==null||!plan.path("confirmed").asBoolean())return false;
        JsonNode steps=plan.path("steps").isArray()?plan.path("steps"):plan.path("data").path("steps");
        String current=plan.path("currentStepId").asText(); boolean after=current.isBlank();
        for(var step:steps) {
            if(step.path("id").asText().equals(current))after=true;
            if(after&&"VIDEO".equals(step.path("kind").asText())
                    &&!Set.of("SUCCEEDED","SKIPPED","CANCELLED").contains(step.path("status").asText()))return true;
        }
        return false;
    }
}
