package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.model.AgentContext;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentBatchRequoteStore.stale;

@Service @RequiredArgsConstructor
public class AgentBatchRequoteApplication {
    private final AgentStore store;
    private final TransactionTemplate tx;
    private final AgentRuntime runtime;
    private final AgentGenerationGateway gateway;
    private final AgentVideoPromptPreparation preparation;
    private final AgentVideoReference references;
    private final VideoSubmitService submissions;
    private final ObjectMapper json;
    public record Command(String clientActionId,Integer expectedVersion,String bindingHash) {
        public Command {
            if(clientActionId==null||clientActionId.isBlank()||clientActionId.length()>64||expectedVersion==null||expectedVersion<1
                    ||bindingHash==null||!bindingHash.matches("[0-9a-f]{64}"))throw BusinessException.badRequest("重新核价参数无效");
        }
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Command fromJson(JsonNode n) {
            if(n==null||!n.isObject()||n.size()!=3||!n.path("clientActionId").isTextual()||!n.path("bindingHash").isTextual()
                    ||!n.path("expectedVersion").isIntegralNumber()||!n.path("expectedVersion").canConvertToInt())throw BusinessException.badRequest("重新核价格式无效");
            return new Command(n.path("clientActionId").asText(),n.path("expectedVersion").asInt(),n.path("bindingHash").asText());
        }
    }
    private record Snapshot(AgentBatchStore.Batch batch,AgentContext context,List<AgentBatchRuntime.Item> items) {}
    private record Prepared(AgentBatchRuntime.Prepared quotes,AgentVideoPromptPreparation.Plan plan,Map<String,String> prompts,
                            List<AgentBatchRuntime.Item> originalBases) {}
    public void requote(long user,long conversation,String grant,Command command) {
        if(command==null)throw BusinessException.badRequest("重新核价参数无效");
        AgentApplication.text(grant,64,"批次编号");
        String key=AgentApplication.text(command.clientActionId(),64,"请求编号");
        String hash=AgentApplication.hashValue(store,List.of("batch-requote",grant,command.expectedVersion(),command.bindingHash()));
        Snapshot initial=tx.execute(t->load(user,conversation,grant,command,key,hash));
        if(initial==null)return;
        // Reference/OSS checks and price lookup occur outside the session transaction. No model or submit calls.
        Prepared prepared;
        try { prepared=prepare(initial); }
        catch(BusinessException e) {
            if(e.getCode()>=500||e.getCode()==429)throw e;
            if(e instanceof VideoPreparationException video&&"VIDEO_QUOTE_UNAVAILABLE".equals(video.code()))
                throw new BusinessException(503,"核价暂不可用，请稍后使用原请求重试");
            throw stale();
        }
        tx.executeWithoutResult(t->{
            Snapshot current=load(user,conversation,grant,command,key,hash);if(current==null)return;
            if(!initial.batch().equals(current.batch())||!json.valueToTree(initial.context()).equals(json.valueToTree(current.context()))
                    ||!json.valueToTree(initial.items()).equals(json.valueToTree(current.items())))throw stale();
            try {
                for(var item:prepared.quotes().items()) {
                    var input=item.quote().inputSnapshot();gateway.validateQuoteSpec(item.quote());
                    if(input.has("referenceImage"))references.revalidateStored(user,current.batch().session(),input.get("referenceImage"),input.path("_reference"));
                }
                if(prepared.plan()!=null) {
                    var base=new AgentBatchRuntime.Prepared(current.batch().planStep(),prepared.originalBases());
                    var checked=preparation.preparePlan(current.context(),base.items().get(0).quote(),base);
                    if(prepared.plan().structured())checked=preparation.structuredPlan(checked);
                    if(!prepared.prompts().equals(store.batchRequotes().prompts(current.batch(),checked)))throw stale();
                }
            } catch(BusinessException e) {if(e.getCode()>=500||e.getCode()==429)throw e;throw stale();}
            var s=store.owned(conversation,user,true);
            String text="重新核价，尚未批准生成。";
            var parts=json.createArrayNode();parts.addObject().put("type","text").put("text",text);
            store.message(s,current.batch().turn(),"USER",text,parts,key,hash);
            store.batchRequotes().create(store,s,current.batch(),prepared.quotes(),prepared.plan(),prepared.prompts());
            store.touch(s);
        });
    }
    private Snapshot load(long user,long conversation,String grant,Command command,String key,String hash) {
        var s=store.owned(conversation,user,true);String replay=store.requestHash(s,key);
        if(replay!=null){if(!replay.equals(hash))throw BusinessException.conflict("同一请求编号不能用于不同内容");return null;}
        var b=store.batches().get(grant);
        if(b==null||!b.session().equals(s.id()))throw BusinessException.notFound("批次不存在");
        if(b.version()!=command.expectedVersion()||!b.binding().equals(command.bindingHash())||!store.batchRequotes().eligible(store,s,b))throw stale();
        for(String request:store.batchRequotes().requests(b))if(submissions.findByRequestId(user,request)!=null)throw stale();
        return new Snapshot(b,runtime.quoteContext(s,store.turn(b.turn()),store.call(b.parent())),store.batchRequotes().items(b));
    }
    private Prepared prepare(Snapshot snapshot) {
        var current=new ArrayList<AgentBatchRuntime.Item>();var original=new ArrayList<AgentBatchRuntime.Item>();
        boolean video="VIDEO".equals(snapshot.items().get(0).quote().mediaType());
        for(var item:snapshot.items()) {
            var old=item.quote();
            if(!"AGENT".equals(old.origin())||video!=old.mediaType().equals("VIDEO"))throw stale();
            var fresh=video?gateway.quoteVideo(snapshot.context(),gateway.videoParameters(old.inputSnapshot())):gateway.quote(old.mediaType(),old.inputSnapshot());
            if(!Objects.equals(old.provider(),fresh.provider())||!Objects.equals(old.modelId(),fresh.modelId())
                    ||!Objects.equals(old.origin(),fresh.origin())||!Objects.equals(old.currency(),fresh.currency()))throw stale();
            var compiled=video?gateway.withPreparedVideoPrompt(fresh,old.inputSnapshot().path("_preparedPrompt").asText()):fresh;
            if(!old.inputSnapshot().equals(compiled.inputSnapshot()))throw stale();
            current.add(new AgentBatchRuntime.Item(item.sceneId(),item.ordinal(),item.source(),fresh));
            var prior=new TaskQuote(old.provider(),old.modelId(),old.modelLabel(),old.mediaType(),fresh.inputSnapshot(),old.amount(),old.currency(),old.origin());
            original.add(new AgentBatchRuntime.Item(item.sceneId(),item.ordinal(),item.source(),prior));
        }
        var next=new AgentBatchRuntime.Prepared(snapshot.batch().planStep(),current);
        if(!video)return new Prepared(next,null,Map.of(),List.of());
        var oldPlan=preparation.preparePlan(snapshot.context(),original.get(0).quote(),new AgentBatchRuntime.Prepared(next.stepId(),original));
        String saved=store.batchRequotes().checkpointHash(snapshot.batch());
        if(!saved.equals(oldPlan.bindingHash()))oldPlan=preparation.structuredPlan(oldPlan);
        var prompts=store.batchRequotes().prompts(snapshot.batch(),oldPlan);
        var checked=preparation.assemble(oldPlan,prompts).batch();
        if(!json.valueToTree(checked.items()).equals(json.valueToTree(snapshot.items())))throw stale();
        var nextPlan=preparation.preparePlan(snapshot.context(),current.get(0).quote(),next);
        if(oldPlan.structured())nextPlan=preparation.structuredPlan(nextPlan);
        return new Prepared(preparation.assemble(nextPlan,prompts).batch(),nextPlan,prompts,List.copyOf(original));
    }
}
