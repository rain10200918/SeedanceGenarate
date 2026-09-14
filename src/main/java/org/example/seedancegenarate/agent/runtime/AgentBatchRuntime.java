package org.example.seedancegenarate.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.exception.BusinessException;
import java.util.*;

/** Pure bounded preparation, outside the Agent transaction; never submits a Task. */
public final class AgentBatchRuntime {
    public record Item(String sceneId,int ordinal,JsonNode source,TaskQuote quote) {}
    public record Prepared(String stepId,List<Item> items) {public Prepared{items=List.copyOf(items);}}
    private AgentBatchRuntime(){}
    public static Prepared prepare(AgentGenerationGateway gateway,AgentContext context,TaskQuote first) {
        if(context.plan()==null)return null;
        JsonNode selected=null;
        for(var step:context.plan().path("steps"))if(step.path("id").equals(context.plan().path("currentStepId")))selected=step;
        if(selected==null||!"STORYBOARD_SCENES".equals(selected.path("scope").asText()))return null;
        if(first==null||first.currency()==null||first.currency().isBlank()||first.provider()==null||first.modelId()==null||first.inputSnapshot()==null)
            throw BusinessException.badRequest("批次报价信息不完整");
        if(!Set.of("IMAGE","VIDEO").contains(first.mediaType())||!first.mediaType().equals(selected.path("kind").asText()))throw BusinessException.badRequest("批次类型与当前步骤不符");
        if(selected.path("scenes").isEmpty()||selected.path("scenes").size()>12)throw BusinessException.badRequest("批次必须包含1至12幕");
        if("VIDEO".equals(first.mediaType())&&selected.path("scenes").size()>1&&"FIRST_FRAME".equals(first.inputSnapshot().path("referenceMode").asText()))
            throw new VideoPreparationException(VideoPreparationException.Reason.REFERENCE);
        var items=new ArrayList<Item>();
        for(var scene:selected.path("scenes")) {
            if("SUCCEEDED".equals(scene.path("status").asText()))continue;
            if(Set.of("FAILED","CANCELLED").contains(scene.path("status").asText()))throw BusinessException.conflict("已有失败分镜，请明确重新启动未完成幕后再次确认费用");
            JsonNode source=scene.path("sourceRef");TaskQuote quote=first;
            boolean current=context.selection()!=null&&context.selection().artifactId().equals(source.path("artifactId").asText())&&context.selection().version()==source.path("version").asInt()&&Objects.equals(context.selection().sceneId(),source.path("sceneId").asText());
            if(!current||"VIDEO".equals(first.mediaType())) {
                String visual=null;JsonNode duration=null;
                for(var artifact:context.artifacts())if(artifact.id().equals(source.path("artifactId").asText())&&artifact.version()==source.path("version").asInt()&&"STORYBOARD".equals(artifact.type()))
                    for(var entry:artifact.data().path("scenes"))if(entry.path("sceneId").equals(source.path("sceneId"))){visual=entry.path("visual").asText(null);duration=entry.get("duration");}
                if(visual==null||visual.isBlank()||visual.length()>4000)throw "VIDEO".equals(first.mediaType())
                        ?new VideoPreparationException(VideoPreparationException.Reason.SCENE).atScene(scene.path("ordinal").asInt())
                        :BusinessException.badRequest("分镜画面说明缺失或过长，请修改后再准备整组生成");
                ObjectNode input="VIDEO".equals(first.mediaType())?gateway.videoParameters(first.inputSnapshot()):first.inputSnapshot().deepCopy();input.put("prompt",visual);
                if("VIDEO".equals(first.mediaType())) {
                    JsonNode baseline=context.videoRepairBaseline(),repair=context.confirmedRepair("VIDEO",source);
                    if(baseline!=null) {
                        for(String field:List.of("model","ratio","referenceImage","referenceMode","visualStyle","megapixels")) {
                            input.remove(field);if(baseline.has(field))input.set(field,baseline.get(field));
                        }
                    }
                    if(repair!=null)for(String field:List.of("model","ratio","referenceImage","referenceMode","visualStyle","megapixels"))
                        if(repair.has(field))input.set(field,repair.get(field));
                    if(duration!=null&&!duration.isNull()) {
                        if(!duration.isIntegralNumber()||!duration.canConvertToInt()||duration.asInt()<1)
                            throw new VideoPreparationException(VideoPreparationException.Reason.SCENE).atScene(scene.path("ordinal").asInt());
                        input.set("duration",duration);
                    }
                    try {quote=gateway.quoteVideo(context,input);}
                    catch(VideoPreparationException e){throw e.atScene(scene.path("ordinal").asInt()).withSource(source);}
                    catch(BusinessException e){
                        if(e.getCode()!=400)throw e;
                        throw new VideoPreparationException(VideoPreparationException.Reason.QUOTE).atScene(scene.path("ordinal").asInt());
                    }
                } else quote=gateway.quote(first.mediaType(),input);
            }
            if(quote.amount()==null||quote.amount().signum()<0||!Objects.equals(quote.currency(),first.currency())||!Objects.equals(quote.mediaType(),first.mediaType()))throw BusinessException.conflict("整组报价币种或类型不一致，请重新准备");
            items.add(new Item(scene.path("id").asText(),scene.path("ordinal").asInt(),source.deepCopy(),quote));
        }
        if(items.isEmpty())throw BusinessException.conflict("本组已全部完成，无需再次生成");
        return new Prepared(selected.path("executionStepId").asText(),items);
    }
}
