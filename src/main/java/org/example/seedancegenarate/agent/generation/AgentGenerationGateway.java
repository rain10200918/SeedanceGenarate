package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.*;
import org.example.seedancegenarate.service.*;
import org.example.seedancegenarate.service.Impl.WalletServiceImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;

/** Adapter to existing generation use cases; no provider calls or wallet implementation here. */
@Component
@RequiredArgsConstructor
public class AgentGenerationGateway {
    private final VideoEngineRegistry engines;
    private final ModelAccessService access;
    private final VideoSubmitService submissions;
    private final VideoTaskService tasks;
    private final ContentModerationPolicy moderation;
    private final ArtifactExpiryPolicy expiry;
    private final ObjectMapper json;
    private final AgentDirectGenerationGateway direct;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentVideoReference videoReference;

    public record TaskView(String taskId,String status,String mediaType,String mediaPath,boolean blocked,boolean expired,String message) {}
    private List<ModelSpec> models(String type) {
        return engines.all().stream().flatMap(e->e.models().stream())
                .filter(m->m.outputType()!=null && m.outputType().name().equals(type) && !m.needImages()
                        && m.imageMin()==0 && !m.needImageOrVideo() && access.isOpen(m.model()))
                .sorted(Comparator.comparing(ModelSpec::model)).toList();
    }
    private List<ModelSpec> videoModels() {
        return engines.all().stream().flatMap(e->e.models().stream()).filter(m->m.outputType()==OutputType.VIDEO && access.isOpen(m.model())
                && ((!m.needImages() && m.imageMin()==0 && !m.needImageOrVideo()) || m.imageInputMode()==ModelSpec.ImageInputMode.REFERENCE_IMAGE
                || m.imageInputMode()==ModelSpec.ImageInputMode.FIRST_FRAME)).sorted(Comparator.comparing(ModelSpec::model)).toList();
    }
    public JsonNode schema(String type) {
        var root=json.createObjectNode().put("type","object").put("additionalProperties",false);
        root.putArray("required").add("model").add("prompt");
        var p=root.putObject("properties");
        var model=p.putObject("model").put("type","string");
        var choices=model.putArray("enum"); var capabilities=model.putArray("x-model-capabilities");
        for(var m:"VIDEO".equals(type)?videoModels():models(type)) {
            choices.add(m.model());
            capabilities.addObject().put("id",m.model()).put("label",m.label()).put("imageInputMode",m.imageInputMode()==null?"UNSPECIFIED":m.imageInputMode().name())
                    .set("parameters",json.valueToTree(Map.of("ratios",list(m.ratios()),"durations",list(m.durations()),
                            "durationMin",m.durationMin(),"durationMax",m.durationMax(),"megapixels",list(m.megapixels()))));
        }
        p.putObject("prompt").put("type","string").put("minLength",1).put("maxLength",4000);
        p.putObject("ratio").put("type","string").put("maxLength",16);
        p.putObject("duration").put("type","integer").put("minimum",1).put("maximum",120);
        p.putObject("megapixels").put("type","number").put("exclusiveMinimum",0);
        if("VIDEO".equals(type)) {
            var ref=p.putObject("referenceImage").put("type","object").put("additionalProperties",false);
            ref.putArray("required").add("artifactId").add("version");
            var rp=ref.putObject("properties");rp.putObject("artifactId").put("type","string").put("minLength",1).put("maxLength",64);
            rp.putObject("version").put("type","integer").put("minimum",1);
            p.putObject("referenceMode").put("type","string").putArray("enum").add("REFERENCE_IMAGE").add("FIRST_FRAME");
            p.putObject("visualStyle").put("type","string").put("minLength",1).put("maxLength",500);
        }
        return root;
    }
    public void validate(String type,JsonNode input) { normalized(type,input,true); }
    private ObjectNode normalized(String type,JsonNode input) {
        return normalized(type,input,false);
    }
    private ObjectNode normalized(String type,JsonNode input,boolean allowInheritedReference) {
        if(!Set.of("IMAGE","VIDEO").contains(type) || input==null || !input.isObject()) throw BusinessException.badRequest("生成参数无效");
        var fields="VIDEO".equals(type)?Set.of("model","prompt","ratio","duration","megapixels","referenceImage","referenceMode","visualStyle"):Set.of("model","prompt","ratio","duration","megapixels");
        input.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw BusinessException.badRequest("生成参数包含不支持的字段");});
        String id=required(input,"model",128), prompt=required(input,"prompt",4000);
        boolean referenced=input.has("referenceImage");
        if(referenced) AgentVideoReference.validateShape(input.get("referenceImage"));
        if(input.has("referenceMode")) {
            if(!Set.of("REFERENCE_IMAGE","FIRST_FRAME").contains(required(input,"referenceMode",32))) throw BusinessException.badRequest("参考图片角色无效");
            if(!referenced && !allowInheritedReference) throw BusinessException.badRequest("参考模式需要参考图片");
        }
        String mode=referenced?(input.has("referenceMode")?required(input,"referenceMode",32):"REFERENCE_IMAGE"):null;
        var matches=(referenced || (allowInheritedReference && "VIDEO".equals(type))?videoModels():models(type)).stream().filter(m->m.model().equals(id)).toList();
        if(matches.size()!=1) throw preparationFailure(type,VideoPreparationException.Reason.MODEL,"所选模型未开放或不支持本阶段的纯文本生成");
        ModelSpec m=matches.get(0);
        if(referenced && ((!"REFERENCE_IMAGE".equals(mode) && !"FIRST_FRAME".equals(mode)) || m.imageInputMode()==null
                || !m.imageInputMode().name().equals(mode) || m.imageMin()>1 || m.imageMax()<1))
            throw new VideoPreparationException(VideoPreparationException.Reason.REFERENCE);
        String ratio=input.has("ratio")?required(input,"ratio",16):list(m.ratios()).stream().findFirst().orElse("16:9");
        if(!list(m.ratios()).contains(ratio)) throw preparationFailure(type,VideoPreparationException.Reason.RATIO,"画幅不在模型支持范围内");
        int duration=8;
        if("VIDEO".equals(type)) {
            if(input.has("duration")&&(!input.get("duration").isIntegralNumber()||!input.get("duration").canConvertToInt()))
                throw new VideoPreparationException(VideoPreparationException.Reason.DURATION);
            duration=input.has("duration")?integer(input.get("duration")):list(m.durations()).stream().findFirst().orElse(Math.max(1,m.durationMin()));
            if(duration<1 || duration>120 || (!list(m.durations()).isEmpty()?!m.durations().contains(duration):duration<m.durationMin()||duration>m.durationMax()))
                throw VideoPreparationException.unsupportedDuration(duration,list(m.durations()),m.durationMin(),m.durationMax());
        } else if(input.has("duration") && integer(input.get("duration"))!=8) throw BusinessException.badRequest("图片生成不支持设置时长");
        var out=json.createObjectNode().put("model",id).put("prompt",prompt).put("ratio",ratio).put("duration",duration);
        if(referenced) { out.set("referenceImage",input.get("referenceImage").deepCopy());out.put("referenceMode",mode); }
        if(input.has("visualStyle")) out.put("visualStyle",required(input,"visualStyle",500));
        if(input.has("megapixels")) {
            var v=input.get("megapixels");
            if(!v.isNumber() || !Double.isFinite(v.doubleValue()) || !list(m.megapixels()).contains(v.doubleValue())) throw preparationFailure(type,VideoPreparationException.Reason.RESOLUTION,"分辨率不在模型支持范围内");
            out.put("megapixels",v.doubleValue());
        } else if(!list(m.megapixels()).isEmpty()) out.put("megapixels",m.megapixels().get(0));
        return out;
    }
    public TaskQuote quote(String type,JsonNode input) {
        ObjectNode snapshot=normalized(type,input);
        if(snapshot.has("referenceImage")) throw BusinessException.badRequest("参考视频报价需要当前对话身份");
        return priced(type,snapshot);
    }
    public ObjectNode videoParameters(JsonNode snapshot) {
        var input=json.createObjectNode();
        for(String field:List.of("model","prompt","ratio","duration","megapixels","referenceImage","referenceMode","visualStyle"))
            if(snapshot.has(field)) input.set(field,snapshot.get(field).deepCopy());
        if(snapshot.has("_originalPrompt")) input.set("prompt",snapshot.get("_originalPrompt"));
        return input;
    }
    public TaskQuote quoteVideo(AgentContext context,JsonNode input) {
        ObjectNode snapshot=normalized("VIDEO",input);
        if(snapshot.has("referenceImage")) {
            if(videoReference==null || context==null || context.userId()==null || context.sessionId()==null) throw BusinessException.badRequest("参考图片身份不可用");
            snapshot.set("_reference",videoReference.resolve(context.userId(),context.sessionId(),snapshot.get("referenceImage")));
        }
        if(snapshot.has("referenceImage") || snapshot.has("visualStyle")) {
            String prompt=snapshot.path("prompt").asText();snapshot.put("_originalPrompt",prompt);
            String prefix=snapshot.has("referenceImage")?"保持参考图片中的角色外观、毛色与特征，不替换主角。\n":"";
            if(snapshot.has("visualStyle")) prefix+="全片视觉风格："+snapshot.path("visualStyle").asText()+"\n";
            snapshot.put("prompt",prefix+prompt);
            if(snapshot.path("prompt").asText().length()>4000)throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_LENGTH);
        }
        return priced("VIDEO",snapshot);
    }
    /** Freeze compiled bytes after preparation; requotes use this pure function, never a model call. */
    public TaskQuote withPreparedVideoPrompt(TaskQuote base,String compiled) {
        if(base==null || !"VIDEO".equals(base.mediaType()) || !"AGENT".equals(base.origin()))
            throw new VideoPreparationException(VideoPreparationException.Reason.BATCH);
        if(compiled==null || compiled.isBlank() || compiled.length()>4000)
            throw new VideoPreparationException(VideoPreparationException.Reason.PROMPT_LENGTH);
        var snapshot=(ObjectNode)base.inputSnapshot().deepCopy();
        if(!snapshot.has("_originalPrompt"))snapshot.set("_originalPrompt",snapshot.get("prompt"));
        snapshot.put("_preparedPrompt",compiled.trim()).put("prompt",compiled.trim());
        return new TaskQuote(base.provider(),base.modelId(),base.modelLabel(),base.mediaType(),snapshot,base.amount(),base.currency(),base.origin());
    }
    private static BusinessException preparationFailure(String type,VideoPreparationException.Reason reason,String legacy) {
        return "VIDEO".equals(type)?new VideoPreparationException(reason):BusinessException.badRequest(legacy);
    }
    private TaskQuote priced(String type,ObjectNode snapshot) {
        ModelSpec model=(snapshot.has("referenceImage")?videoModels():models(type)).stream().filter(m->m.model().equals(snapshot.path("model").asText())).findFirst().orElseThrow(()->BusinessException.badRequest("模型当前不可用"));
        VideoSubmitService.PriceEstimate price;
        try { price=submissions.estimate(model.provider(),model.model(),snapshot.path("duration").intValue()); }
        catch(BusinessException failure) {
            if("VIDEO".equals(type)&&Integer.valueOf(400).equals(failure.getCode()))
                throw new VideoPreparationException(VideoPreparationException.Reason.QUOTE);
            throw failure;
        }
        if(!model.provider().equals(price.provider()) || !model.model().equals(price.model()) || !type.equals(price.outputType())
                || !Objects.equals(price.duration(),snapshot.path("duration").intValue()) || price.amount()==null || price.amount().signum()<0
                || price.currency()==null || price.currency().isBlank()) throw BusinessException.conflict("模型或报价发生变化，请重新选择");
        return new TaskQuote(price.provider(),price.model(),model.label(),type,snapshot,price.amount(),price.currency());
    }
    private static int integer(JsonNode v) {
        if(!v.isIntegralNumber() || !v.canConvertToInt()) throw BusinessException.badRequest("时长必须是整数"); return v.intValue();
    }
    private static String required(JsonNode n,String key,int max) {
        var v=n.get(key); if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>max) throw BusinessException.badRequest("生成文本参数无效");
        return v.textValue().trim();
    }
    private static <T> List<T> list(List<T> values) { return values==null?List.of():values; }
    public String findAccepted(long userId,String requestId) {
        VideoTask existing=submissions.findAcceptedByRequestId(userId,requestId);
        return existing==null?null:existing.businessTaskId();
    }
    public String submit(long userId,TaskQuote approved,String requestId) throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("生成提交不能包在Agent事务内");
        if(userId<1 || requestId==null || requestId.isBlank() || requestId.length()>128 || approved==null)
            throw new GenerationRejectedException("生成确认信息无效，请重新发起");
        String accepted=findAccepted(userId,requestId);
        if(accepted!=null) return accepted;
        TaskQuote current;
        try {
            if("DIRECT".equals(approved.origin())) current=direct.requote(userId,approved.mediaType(),approved.inputSnapshot());
            else if("VIDEO".equals(approved.mediaType()) && approved.inputSnapshot().has("_originalPrompt")) {
                var ref=approved.inputSnapshot().path("_reference");
                if(approved.inputSnapshot().has("referenceImage") && ref.path("userId").asLong()!=userId) throw BusinessException.forbidden("参考图片不属于当前用户");
                current=quoteVideo(new AgentContext(userId,ref.path("sessionId").asText(),null,null,null,null,List.of(),List.of(),0),videoParameters(approved.inputSnapshot()));
                if(approved.inputSnapshot().has("_preparedPrompt"))
                    current=withPreparedVideoPrompt(current,approved.inputSnapshot().path("_preparedPrompt").asText());
            } else current=quote(approved.mediaType(),approved.inputSnapshot());
        }
        catch(BusinessException e) { throw new GenerationRejectedException("模型或参数已变化，请重新生成报价并确认"); }
        if(!Objects.equals(current.provider(),approved.provider()) || !Objects.equals(current.modelId(),approved.modelId())
                || !Objects.equals(current.origin(),approved.origin()) || !Objects.equals(current.currency(),approved.currency()) || approved.amount()==null
                || current.amount().compareTo(approved.amount())!=0 || !current.inputSnapshot().equals(approved.inputSnapshot()))
            throw new GenerationRejectedException("报价或参数已变化，请重新确认费用");
        JsonNode input=current.inputSnapshot();
        var request=new VideoSubmitService.SubmitRequest(userId,current.provider(),current.modelId(),input.path("prompt").asText(),
                references(current,"image"),references(current,"video"),references(current,"audio"),input.path("duration").intValue(),input.path("ratio").asText("16:9"),
                input.has("megapixels")?input.get("megapixels").doubleValue():null,null,requestId,null,
                input.has("_reference")?List.of(new StoredImageReferences.Reference(input.path("_reference").path("taskId").asText(),input.path("_reference").path("objectKey").asText())):List.of());
        var ceiling=new VideoSubmitService.PriceEstimate(current.provider(),current.modelId(),input.path("duration").intValue(),
                current.mediaType(),null,approved.amount(),approved.currency());
        try { return submissions.submitApproved(request,ceiling).businessTaskId(); }
        catch(BusinessException | WalletServiceImpl.InsufficientBalanceException | ConcurrencyLimitExceededException e) {
            // Only a proven absence after the domain's compensation is safe to reject. Never overwrite an uncertain acceptance.
            if(submissions.findByRequestId(userId,requestId)!=null) throw e;
            String reason=e instanceof WalletServiceImpl.InsufficientBalanceException?"余额不足，请充值后重新发起":
                    e instanceof ConcurrencyLimitExceededException?"当前生成数量已达上限，请稍后重新发起":"模型或报价不可用，请重新确认后生成";
            throw new GenerationRejectedException(reason);
        }
    }
    private static List<String> references(TaskQuote quote,String type) {
        if(!"DIRECT".equals(quote.origin()))return List.of();
        var urls=new ArrayList<String>();
        for(JsonNode ref:quote.inputSnapshot().path("references"))if(type.equals(ref.path("type").asText()))urls.add(ref.path("url").asText());
        return List.copyOf(urls);
    }
    VideoTask owned(long userId,String taskId) {
        if(userId<1 || taskId==null || taskId.isBlank() || taskId.length()>128) throw BusinessException.notFound("任务不存在");
        VideoTask task=tasks.getOne(Wrappers.<VideoTask>lambdaQuery().eq(VideoTask::getUserId,userId)
                .and(w->w.eq(VideoTask::getBizTaskId,taskId).or().eq(VideoTask::getTaskId,taskId)),false);
        if(task==null || !Objects.equals(task.getUserId(),userId)) throw BusinessException.notFound("任务不存在");
        return task;
    }
    public TaskView read(long userId,String taskId) {
        return view(owned(userId,taskId));
    }
    private TaskView view(VideoTask task) {
        boolean blocked=moderation.isBlocked(task), expired=expiry.isExpired(task);
        String message=blocked?moderation.blockedMessage(task):expired?expiry.expiredMessage():
                "FAILED".equals(task.getStatus())?"生成未完成，请查看任务详情":task.processingMessage();
        String media=null;
        if("SUCCESS".equals(task.getStatus()) && !blocked && !expired && "OSS".equals(task.getArtifactStorageType())
                && task.getArtifactKey()!=null && !task.getArtifactKey().isBlank())
            media="/api/agent/media/"+java.net.URLEncoder.encode(task.businessTaskId(),java.nio.charset.StandardCharsets.UTF_8);
        return new TaskView(task.businessTaskId(),task.getStatus(),task.getOutputType(),media,blocked,expired,message);
    }
    String mediaKey(long userId,String taskId) {
        VideoTask task=owned(userId,taskId); TaskView result=view(task);
        if(result.blocked()) throw BusinessException.forbidden("此作品暂不可查看");
        if(result.expired()) throw new BusinessException(410,"此作品已过期");
        if(result.mediaPath()==null) throw BusinessException.notFound("作品暂不可预览，请查看任务详情");
        return task.getArtifactKey();
    }
}
