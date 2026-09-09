package org.example.seedancegenarate.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest.Attachment;
import org.example.seedancegenarate.engine.*;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.*;
import org.springframework.stereotype.Component;
import org.example.seedancegenarate.exception.BusinessException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Human-controlled generation preparation; never invokes a planner, provider, or wallet. */
@Component
@RequiredArgsConstructor
public class AgentDirectGenerationGateway {
    private final VideoEngineRegistry engines;
    private final ModelAccessService access;
    private final VideoSubmitService submissions;
    private final ConversationMediaResolver media;
    private final UserAssetMapper assets;
    private final OssConfig oss;
    private final ObjectMapper json;
    private static final Set<String> INPUT=Set.of("provider","model","prompt","ratio","duration","megapixels");
    public List<ModelSpec> models() {
        return engines.all().stream().flatMap(e->e.models().stream())
                .filter(m->m.outputType()!=null && Set.of("IMAGE","VIDEO","AUDIO").contains(m.outputType().name()) && access.isOpen(m.model()))
                .sorted(Comparator.comparing(ModelSpec::provider).thenComparing(ModelSpec::model)).toList();
    }
    public TaskQuote prepareDirect(long user,String type,JsonNode input,List<Attachment> attachments,ConversationMediaResolver.LocalFiles files) {
        if(user<1) throw BusinessException.forbidden("请先登录");
        ObjectNode snapshot=normalize(type,input);
        ModelSpec model=model(type,snapshot);
        snapshot.put("ownerId",user).set("references",references().prepare(user,attachments,files,model));
        return price(type,model,snapshot);
    }
    public TaskQuote requote(long user,String type,JsonNode trustedSnapshot) {
        if(trustedSnapshot==null || !trustedSnapshot.isObject() || !trustedSnapshot.path("ownerId").isIntegralNumber()
                || !trustedSnapshot.path("ownerId").canConvertToLong() || user<1 || trustedSnapshot.path("ownerId").longValue()!=user)
            throw BusinessException.forbidden("生成确认不属于当前用户");
        trustedSnapshot.fieldNames().forEachRemaining(k->{if(!INPUT.contains(k) && !Set.of("ownerId","references").contains(k))throw BusinessException.badRequest("确认参数无效");});
        ObjectNode params=((ObjectNode)trustedSnapshot).deepCopy();params.remove(List.of("ownerId","references"));
        ObjectNode snapshot=normalize(type,params);ModelSpec model=model(type,snapshot);
        snapshot.put("ownerId",user).set("references",references().restore(user,trustedSnapshot.get("references"),model));
        return price(type,model,snapshot);
    }
    private DirectReferenceMedia references() { return new DirectReferenceMedia(media,assets,oss,json); }
    private ModelSpec model(String type,JsonNode input) {
        var candidates=models().stream().filter(m->m.outputType().name().equals(type) && m.provider().equals(input.path("provider").asText())
                && m.model().equals(input.path("model").asText())).toList();
        if(candidates.size()!=1)throw BusinessException.badRequest("所选模型未开放或不支持此生成类型");
        return candidates.get(0);
    }
    private ObjectNode normalize(String type,JsonNode input) {
        if(type==null || !Set.of("IMAGE","VIDEO","AUDIO").contains(type) || input==null || !input.isObject())throw BusinessException.badRequest("生成参数无效");
        input.fieldNames().forEachRemaining(k->{if(!INPUT.contains(k))throw BusinessException.badRequest("生成参数包含不支持的字段");});
        var out=json.createObjectNode().put("provider",text(input,"provider",64)).put("model",text(input,"model",128)).put("prompt",text(input,"prompt",4000));
        ModelSpec m=model(type,out);
        if("AUDIO".equals(type)) {
            if(input.has("ratio") || input.has("megapixels"))throw BusinessException.badRequest("音频不支持画幅或分辨率");
        } else {
            String ratio=input.has("ratio")?text(input,"ratio",16):list(m.ratios()).stream().findFirst().orElse("16:9");
            if(!list(m.ratios()).contains(ratio))throw BusinessException.badRequest("画幅不在模型支持范围内");out.put("ratio",ratio);
        }
        int duration=8;
        if("IMAGE".equals(type)) {
            if(input.has("duration") && integer(input.get("duration"))!=8)throw BusinessException.badRequest("图片生成不支持设置时长");
        } else {
            duration=input.has("duration")?integer(input.get("duration")):list(m.durations()).stream().findFirst().orElse(Math.max(1,m.durationMin()));
            if(duration<1 || (!list(m.durations()).isEmpty()?!m.durations().contains(duration):duration<m.durationMin() || duration>m.durationMax()))
                throw BusinessException.badRequest("时长不在模型支持范围内");
        }
        out.put("duration",duration);
        if(input.has("megapixels")) {
            JsonNode n=input.get("megapixels");
            if(!n.isNumber() || !Double.isFinite(n.doubleValue()) || !list(m.megapixels()).contains(n.doubleValue()))throw BusinessException.badRequest("分辨率不在模型支持范围内");
            out.put("megapixels",n.doubleValue());
        } else if(!list(m.megapixels()).isEmpty() && !"AUDIO".equals(type))out.put("megapixels",m.megapixels().get(0));
        return out;
    }
    private TaskQuote price(String type,ModelSpec model,ObjectNode snapshot) {
        var p=submissions.estimate(model.provider(),model.model(),snapshot.path("duration").intValue());
        if(p==null || !model.provider().equals(p.provider()) || !model.model().equals(p.model()) || !type.equals(p.outputType())
                || !Objects.equals(p.duration(),snapshot.path("duration").intValue()) || p.amount()==null || p.amount().signum()<0 || p.currency()==null || p.currency().isBlank())
            throw BusinessException.conflict("模型或报价发生变化，请重新选择");
        return new TaskQuote(p.provider(),p.model(),model.label(),type,snapshot,p.amount(),p.currency(),"DIRECT");
    }
    private static String text(JsonNode input,String key,int max) {
        JsonNode n=input.get(key);if(n==null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length()>max)throw BusinessException.badRequest("生成文本参数无效");return n.textValue().trim();
    }
    private static int integer(JsonNode n) { if(!n.isIntegralNumber() || !n.canConvertToInt())throw BusinessException.badRequest("时长必须是整数");return n.intValue(); }
    private static <T> List<T> list(List<T> values) { return values==null?List.of():values; }
}
