package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.skill.StoryboardVideoCapabilities;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @RequiredArgsConstructor
public class AgentRepairApplication {
    private final AgentStore store;
    private final TransactionTemplate tx;
    private final AsyncJobService jobs;
    private final VideoEngineRegistry engines;
    private final ModelAccessService access;
    private final AgentGenerationGateway gateway;
    private final AgentVideoReference references;
    private final ObjectMapper json;
    public record Command(String clientActionId,Long expectedWorkspaceVersion,String failureId,String optionId) {
        public Command {
            if(clientActionId==null||clientActionId.isBlank()||clientActionId.length()>64||failureId==null||failureId.isBlank()||failureId.length()>128
                    ||optionId==null||optionId.isBlank()||optionId.length()>128||expectedWorkspaceVersion==null||expectedWorkspaceVersion<0||expectedWorkspaceVersion>9007199254740991L)
                throw BusinessException.badRequest("修复请求字段无效");
        }
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Command fromJson(JsonNode body) {
            if(body==null||!body.isObject()||body.size()!=4)throw BusinessException.badRequest("修复请求格式无效");
            for(String key:List.of("clientActionId","failureId","optionId"))
                if(!body.path(key).isTextual())throw BusinessException.badRequest("修复请求字段类型无效");
            var version=body.path("expectedWorkspaceVersion");
            if(!version.isIntegralNumber()||!version.canConvertToLong()||version.asLong()<0||version.asLong()>9007199254740991L)
                throw BusinessException.badRequest("创作版本无效");
            return new Command(body.path("clientActionId").asText(),version.asLong(),body.path("failureId").asText(),body.path("optionId").asText());
        }
    }
    public JsonNode suggestions(long user,long conversation) {
        var s=store.owned(conversation,user,false);
        return advice(s,store.repairs().current(store,s));
    }
    public void confirm(long user,long conversation,Command command) {
        if(command==null||command.expectedWorkspaceVersion()==null||command.expectedWorkspaceVersion()<0||command.expectedWorkspaceVersion()>9007199254740991L)
            throw BusinessException.badRequest("修复请求缺少有效版本");
        String key=AgentApplication.text(command.clientActionId(),64,"请求编号");
        AgentApplication.text(command.failureId(),128,"失败编号");AgentApplication.text(command.optionId(),128,"选项编号");
        String hash=AgentApplication.hashValue(store,command);
        var initial=store.owned(conversation,user,false);
        String accepted=store.repairs().replay(initial,key);
        if(accepted!=null){if(!hash.equals(accepted))throw BusinessException.conflict("同一请求编号不能用于不同内容");return;}
        var preparedFailure=store.repairs().current(store,initial);
        // Capability and OSS checks run before acquiring the session lock or opening a transaction.
        Option selected=preparedFailure==null||preparedFailure.path("workspaceVersion").asLong()!=command.expectedWorkspaceVersion()
                ||!command.failureId().equals(preparedFailure.path("failureId").asText())?null:
                options(initial,preparedFailure).stream().filter(o->o.id().equals(command.optionId())).findFirst().orElse(null);
        tx.executeWithoutResult(transaction->{
            var s=store.owned(conversation,user,true);String replay=store.repairs().replay(s,key);
            if(replay!=null){if(!hash.equals(replay))throw BusinessException.conflict("同一请求编号不能用于不同内容");return;}
            if(store.requestHash(s,key)!=null)throw BusinessException.conflict("请求编号已用于其他操作");
            var w=store.workspace(s);var failure=store.repairs().current(store,s);
            if(w.path("version").asLong()!=command.expectedWorkspaceVersion()||failure==null||!failure.equals(preparedFailure))
                throw BusinessException.conflict("修复建议已失效，请刷新后重新选择");
            if(selected==null)throw BusinessException.conflict("模型或参考已变化，请刷新修复建议");
            var currentModels=engines.all().stream().flatMap(e->e.models().stream()).filter(m->m.model().equals(selected.params().path("model").asText())).toList();
            if(currentModels.size()!=1||!access.isOpen(currentModels.get(0).model())||!json.valueToTree(currentModels.get(0)).equals(selected.model()))
                throw BusinessException.conflict("模型能力已变化，请刷新修复建议");
            try {gateway.validate("VIDEO",((ObjectNode)selected.params()).deepCopy().put("prompt","分镜能力检查"));}
            catch(BusinessException error) {throw BusinessException.conflict("模型能力已变化，请刷新修复建议");}
            if(selected.reference()!=null) {
                try {references.revalidateStored(s.userId(),s.id(),selected.params().path("referenceImage"),selected.reference());}
                catch(BusinessException error) {throw BusinessException.conflict("参考图片已变化，请刷新修复建议");}
            }
            var old=store.lockedTurn(s.activeTurnId());var call=store.call(failure.path("callId").asText());
            store.repairs().confirm(store,s,failure,selected.params(),key,hash);
            var next=store.turn(store.newTurn(s,old.channel(),s.goal()));
            String binding=store.modelBinding(old);if(binding!=null)store.bindModel(next,binding);
            String nextCall=store.repairs().restart(store,s,next,call);
            String text="已采用局部修复，正在重新准备；完成后仍需确认新的报价。";
            var parts=json.createArrayNode();parts.addObject().put("type","text").put("text",text);
            store.message(s,next.id(),"USER",text,parts,key,hash);
            jobs.enqueue(AgentRuntime.SKILL_JOB,nextCall,store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),nextCall)));
            store.touch(s);
        });
    }
    private ObjectNode advice(AgentRows.Session s,JsonNode f) {
        var result=json.createObjectNode().put("workspaceVersion",store.workspace(s).path("version").asLong())
                .putNull("failureId").putNull("callId").putNull("code").put("message","当前没有可采用的局部修复")
                .put("reason","当前没有可信的未提交准备失败，请检查原任务状态。")
                .putNull("target").put("impact","").put("quoteStatus","NOT_APPLICABLE");
        var options=result.putArray("options");if(f==null)return result;
        for(String key:List.of("workspaceVersion","failureId","callId","code","message","target"))result.set(key,f.path(key));
        result.put("impact","STORYBOARD".equals(f.path("target").path("kind").asText())
                ?"仅恢复未完成的分镜步骤，保留脚本；后续视频仍需准备并确认新报价。"
                :"仅替换目标视频规格，保留成功图片及视频；完整绑定变化可能使本批未完成提示词重新准备，随后确认新报价。");
        result.put("quoteStatus","REQUIRES_NEW_QUOTE");
        for(var option:options(s,f))options.add(option.view());
        if(!options.isEmpty())result.putNull("reason");else result.put("reason","没有同时满足原时长、画幅和参考角色的可用模型或图片，请人工调整方案；系统未修改总时长。");
        return result;
    }
    private record Option(String id,JsonNode params,ObjectNode view,JsonNode reference,JsonNode model) {}
    private List<Option> options(AgentRows.Session s,JsonNode f) {
        ObjectNode base=f.path("input").deepCopy();base.remove("prompt");
        // Same omitted-role semantics as Gateway: an explicit image defaults to REFERENCE_IMAGE.
        if(base.has("referenceImage")&&!base.has("referenceMode"))base.put("referenceMode","REFERENCE_IMAGE");
        var target=f.path("target");var source=target.path("sourceRef");JsonNode creation=null;
        if(source.hasNonNull("artifactId")) {
            var a=store.artifactVersion(s,source.path("artifactId").asText(),source.path("version").asInt());
            if(a.data()!=null) {
                creation=a.data().get("creationSpec");
                if("VIDEO".equals(target.path("kind").asText()))for(var scene:a.data().path("scenes"))
                    if(scene.path("sceneId").equals(source.path("sceneId"))&&scene.hasNonNull("duration"))base.set("duration",scene.get("duration"));
            }
        }
        var w=store.workspace(s);var planRef=w.path("planRef");
        if(planRef.hasNonNull("artifactId")) {
            var data=store.artifactVersion(s,planRef.path("artifactId").asText(),planRef.path("version").asInt()).data();
            if(creation==null&&data!=null)creation=data.get("creationSpec");
        }
        if(creation!=null&&creation.hasNonNull("ratio"))base.set("ratio",creation.get("ratio"));
        var models=engines.all().stream().flatMap(e->e.models().stream()).filter(m->m.outputType()==OutputType.VIDEO)
                .sorted(Comparator.comparing(ModelSpec::model)).toList();
        // Preserve the original model's defaults when omitted; never silently pick replacement defaults.
        for(var model:models)if(model.model().equals(base.path("model").asText())) {
            if(!base.has("ratio")&&model.ratios()!=null&&!model.ratios().isEmpty())base.put("ratio",model.ratios().get(0));
            if(!base.has("duration")&&"VIDEO".equals(target.path("kind").asText()))base.put("duration",model.durations()!=null&&!model.durations().isEmpty()?model.durations().get(0):Math.max(1,model.durationMin()));
            if(!base.has("referenceMode")&&(model.needImages()||model.imageMin()>0||model.needImageOrVideo())&&model.imageInputMode()!=null
                    &&Set.of(ModelSpec.ImageInputMode.REFERENCE_IMAGE,ModelSpec.ImageInputMode.FIRST_FRAME).contains(model.imageInputMode()))
                base.put("referenceMode",model.imageInputMode().name());
        }
        if("VIDEO".equals(target.path("kind").asText())&&(!base.has("duration")||!base.path("duration").isIntegralNumber()))return List.of();
        var refs=new ArrayList<JsonNode>();
        if(base.has("referenceImage")||base.has("referenceMode")) {
            if(base.has("referenceImage"))refs.add(base.get("referenceImage"));
            for(var a:store.artifacts(s))if("IMAGE".equals(a.type())&&refs.size()<49) {
                var ref=json.createObjectNode().put("artifactId",a.id()).put("version",a.version());if(!refs.contains(ref))refs.add(ref);
            }
        } else refs.add(json.nullNode());
        var result=new ArrayList<Option>();
        var resolved=new HashMap<JsonNode,JsonNode>(); // This request only; also cache unavailable references.
        for(var model:models)for(var ref:refs) {
            if(result.size()==12)return result;
            if(!access.isOpen(model.model()))continue;
            if(model.model().equals(base.path("model").asText())&&Objects.equals(ref.isNull()?null:ref,base.get("referenceImage")))continue;
            ObjectNode params=base.deepCopy().put("model",model.model()).put("prompt","分镜能力检查");
            JsonNode image=null;
            if(!ref.isNull()) {
                params.set("referenceImage",ref);if(!params.has("referenceMode"))params.put("referenceMode","REFERENCE_IMAGE");
                if(!resolved.containsKey(ref)) {
                    try{resolved.put(ref,references.resolve(s.userId(),s.id(),ref));}
                    catch(BusinessException error){if(Set.of(400,403,404).contains(error.getCode()))resolved.put(ref,null);else throw error;}
                }
                image=resolved.get(ref);if(image==null)continue;
            } else if(model.needImages()||model.imageMin()>0||model.needImageOrVideo())continue;
            try {gateway.validate("VIDEO",params);}
            catch(BusinessException error){if(error.getCode()==400)continue;throw error;}
            if(creation!=null&&creation.hasNonNull("totalDurationSeconds")&&"STORYBOARD".equals(target.path("kind").asText())
                    &&!StoryboardVideoCapabilities.reachable(model,params,creation.path("totalDurationSeconds").asInt()))continue;
            var fingerprint=json.createObjectNode();fingerprint.set("params",params);fingerprint.set("model",json.valueToTree(model));fingerprint.set("reference",image);
            fingerprint.set("creationSpec",creation);fingerprint.set("failure",f);
            String optionId=AgentApplication.hashValue(store,fingerprint);
            var view=json.createObjectNode().put("optionId",optionId).put("label","采用 "+model.label())
                    .put("description",image==null?"保留原时长、画幅与画风，重新准备。":"采用有效参考图片，保留原时长、画幅与参考角色，重新准备。");
            var spec=view.putObject("spec").put("model",model.model()).put("modelLabel",model.label());
            if(params.has("ratio"))spec.set("ratio",params.get("ratio"));
            if(params.has("duration"))spec.set("durationSeconds",params.get("duration"));
            if(creation!=null&&creation.has("totalDurationSeconds"))spec.set("totalDurationSeconds",creation.get("totalDurationSeconds"));
            if(image!=null) {
                var shown=spec.putObject("referenceImage");shown.set("artifactId",ref.get("artifactId"));shown.set("version",ref.get("version"));
                shown.set("title",image.path("title"));shown.set("mediaPath",image.path("mediaPath"));spec.set("referenceMode",params.get("referenceMode"));
            }
            params.remove("prompt");result.add(new Option(optionId,params,view,image==null?null:image.deepCopy(),json.valueToTree(model)));
        }
        return result;
    }
}
