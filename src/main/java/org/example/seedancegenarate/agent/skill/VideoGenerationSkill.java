package org.example.seedancegenarate.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.exception.BusinessException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoGenerationSkill implements TaskSkill {
    private final AgentGenerationGateway gateway;
    public SkillDescriptor descriptor() { return new SkillDescriptor("video-generation","2","视频报价；source引用文本分镜，referenceImage才是IMAGE作品准确版本。继承已确认计划角色参考与visualStyle；按模型显式图片角色能力准备，不接受URL，不默默文生降级。用户批准后生成。",StructuredSkillSupport.taskSchema(gateway.schema("VIDEO")),"VIDEO"); }
    public void validate(JsonNode input) {
        var params=StructuredSkillSupport.taskInput(input);
        try {gateway.validate("VIDEO",params);}
        catch(org.example.seedancegenarate.agent.generation.VideoPreparationException failure) {
            // Live capability failures belong to the durable preparation Call, before any quote/approval.
            if(!java.util.Set.of("VIDEO_MODEL_UNAVAILABLE","VIDEO_DURATION_UNSUPPORTED","VIDEO_REFERENCE_UNSUPPORTED").contains(failure.code()))throw failure;
        }
    }
    public TaskQuote quote(AgentContext context,JsonNode input) {
        ObjectNode params=(ObjectNode)StructuredSkillSupport.taskInput(input);
        var source=StructuredSkillSupport.source(context,input);
        JsonNode repair=context.confirmedRepair("VIDEO",source==null?null:new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(source));
        JsonNode boardRepair=context.confirmedStoryboardRepair(source);
        JsonNode sourceData=null;
        if(source!=null) {
            var artifact=StructuredSkillSupport.resolve(context,source);
            if(java.util.Set.of("SCRIPT","STORYBOARD").contains(artifact.type()))sourceData=artifact.data();
            if("STORYBOARD".equals(artifact.type())&&artifact.data()!=null&&artifact.data().hasNonNull("videoCapabilities")) {
                var capabilities=artifact.data().path("videoCapabilities");
                for(String field:java.util.List.of("model","ratio","referenceImage","referenceMode"))if(capabilities.hasNonNull(field)) {
                    if(repair!=null&&repair.has(field)) {params.set(field,repair.get(field));continue;}
                    if(params.has(field)&&!params.get(field).equals(capabilities.get(field)))
                        throw BusinessException.conflict("视频规格与所引用分镜绑定的模型、画幅或参考角色不一致，请重新确认创作方案");
                    params.set(field,capabilities.get(field).deepCopy());
                }
                if(source.sceneId()!=null) {
                    JsonNode scene=null;
                    for(var item:artifact.data().path("scenes"))if(source.sceneId().equals(item.path("sceneId").asText()))scene=item;
                    if(scene==null)throw BusinessException.badRequest("该版本中没有所选分镜场景");
                    if(scene.hasNonNull("duration")) {
                        if(params.has("duration")&&!params.get("duration").equals(scene.get("duration")))
                            throw BusinessException.conflict("视频时长与所引用分镜不一致，请先调整分镜并重新确认");
                        params.set("duration",scene.get("duration"));
                    }
                }
            }
        }
        if(context.plan()!=null&&context.plan().path("confirmed").asBoolean()) {
            var plan=context.plan().path("data");
            for(String field:java.util.List.of("referenceImage","visualStyle"))if(plan.hasNonNull(field)) {
                if(repair!=null&&repair.has(field)) {params.set(field,repair.get(field));continue;}
                if(boardRepair!=null&&boardRepair.has(field)&&sourceData!=null&&boardRepair.get(field).equals(sourceData.path("videoCapabilities").path(field))) {
                    params.set(field,boardRepair.get(field));continue;
                }
                if(params.has(field)&&!params.get(field).equals(plan.get(field)))throw BusinessException.conflict("生成规格与已采用计划的角色或画风不一致，请先修改计划并重新确认");
                params.set(field,plan.get(field).deepCopy());
            }
            if(plan.hasNonNull("referenceImage")) {
                if(params.has("referenceMode")&&!"REFERENCE_IMAGE".equals(params.path("referenceMode").asText()))throw BusinessException.badRequest("整片角色参考不能替换为首帧输入");
                params.put("referenceMode","REFERENCE_IMAGE");
            }
        }
        JsonNode target=CreationSpecSupport.resolve(context,sourceData);
        if(target!=null) {
            if(target.hasNonNull("ratio")) {
                if(params.has("ratio")&&!params.get("ratio").equals(target.get("ratio")))
                    throw BusinessException.conflict("视频画幅与创作规格不一致，请重新确认方案");
                params.set("ratio",target.get("ratio"));
            }
            if(sourceData!=null&&sourceData.has("scenes")) {
                StoryboardVideoCapabilities.validateTotal(target,sourceData.path("scenes"));
                JsonNode scene=null;
                for(var item:sourceData.path("scenes"))if(source.sceneId()!=null&&source.sceneId().equals(item.path("sceneId").asText()))scene=item;
                if(scene==null||!scene.path("duration").isIntegralNumber())throw BusinessException.badRequest("请选择有明确时长的分镜场景");
                if(params.has("duration")&&!params.get("duration").equals(scene.get("duration")))throw BusinessException.conflict("视频时长与分镜不一致，请重新确认方案");
                params.set("duration",scene.get("duration"));
            }
            else if(target.hasNonNull("totalDurationSeconds")) {
                if(params.has("duration")&&!params.get("duration").equals(target.get("totalDurationSeconds")))
                    throw BusinessException.conflict("单段视频时长与创作总时长不同，请先创建分镜或调整方案");
                params.set("duration",target.get("totalDurationSeconds"));
            }
        }
        if(repair!=null)for(String field:java.util.List.of("model","ratio","duration","referenceImage","referenceMode","visualStyle","megapixels"))
            if(repair.has(field))params.set(field,repair.get(field));
        return gateway.quoteVideo(context,params);
    }
}
