package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

@Service
@RequiredArgsConstructor
public class AgentApprovalApplication {
    private final AgentStore store;
    private final AgentApprovalStore approvals;
    private final TransactionTemplate tx;
    private final AsyncJobService jobs;
    private final AgentGenerationGateway gateway;
    private final ObjectMapper json;
    public record Answer(String clientActionId,Integer expectedVersion,String decision) {}

    public void answer(long user,long conversation,String approvalId,Answer answer) {
        if(answer==null || answer.expectedVersion()==null || answer.expectedVersion()<1 || !Set.of("APPROVE","REJECT").contains(Objects.toString(answer.decision(),"")))
            throw BusinessException.badRequest("请选择确认生成或取消");
        String key=AgentApplication.text(answer.clientActionId(),64,"请求编号");
        String hash=AgentApplication.hashValue(store,List.of("approval",approvalId,answer.expectedVersion(),answer.decision()));
        tx.executeWithoutResult(t->{
            Session s=store.owned(conversation,user,true);
            String previous=store.requestHash(s,key);
            if(previous!=null) { if(!previous.equals(hash)) throw BusinessException.conflict("同一请求编号不能用于不同内容"); return; }
            Approval a=approvals.locked(approvalId); Turn turn=store.lockedTurn(s.activeTurnId());
            if(a==null || !a.sessionId().equals(s.id())) throw BusinessException.notFound("确认单不存在");
            if(store.batches().grant(a.id())!=null)throw BusinessException.conflict("该项目属于整组确认，请使用整组费用确认卡");
            if(turn==null || !a.turnId().equals(turn.id()) || a.epoch()!=turn.epoch() || !"WAITING_APPROVAL".equals(turn.status())
                    || a.version()!=answer.expectedVersion() || !"PENDING".equals(a.status()) || !a.expires().isAfter(store.now()))
                throw BusinessException.conflict("这个确认单已处理或过期，请刷新对话");
            if(!store.workspaceMatches(s,store.call(a.callId()))) throw BusinessException.conflict("创作计划或引用已变化，请重新确认");
            boolean accepted="APPROVE".equals(answer.decision());
            approvals.answer(a.id(),accepted?"APPROVED":"REJECTED");
            String text=accepted?"确认生成":"取消这次生成";
            store.message(s,turn.id(),"USER",text,json.valueToTree(List.of(Map.of("type","text","text",text))),key,hash);
            store.executionStatus(turn.id(),accepted?"SUBMITTING":"CANCELLED",null);
            store.callStatus(a.callId(),accepted?"APPROVED":"CANCELLED",null);
            if(accepted) jobs.enqueue(AgentGenerationRuntime.JOB,a.id(),a.id());
            store.touch(s);
        });
    }
    public TaskQuote quote(Approval a) {
        try { return json.readValue(a.quote(),TaskQuote.class); }
        catch(Exception e) { throw new IllegalStateException("生成确认数据无效",e); }
    }
    public void project(Session s,List<AgentViews.Message> messages) {
        store.batches().project(store,s,messages);
        var ids=new LinkedHashSet<String>();
        for(var message:messages)for(var part:message.parts())
            if(part.isObject() && Set.of("approval","task").contains(part.path("type").asText()))ids.add(part.path("approvalId").asText());
        var projected=approvals.projectApprovals(s,ids);
        var contexts=approvals.projectContexts(s,projected.values().stream().map(Approval::callId).distinct().toList());
        var views=new HashMap<String,java.util.Optional<AgentGenerationGateway.TaskView>>();
        java.util.function.Function<String,AgentGenerationGateway.TaskView> mediaView=id->views.computeIfAbsent(id,key->{
            try {return java.util.Optional.of(gateway.read(s.userId(),key));}
            catch(BusinessException e){return java.util.Optional.empty();}
        }).orElseThrow(()->BusinessException.notFound("作品暂不可用"));
        for(var message:messages) for(var part:message.parts()) {
            if(!(part instanceof ObjectNode object)) continue;
            String type=part.path("type").asText();
            if("batch_approval".equals(type)) {
                for(var item:part.path("items"))if(item instanceof ObjectNode media&&media.hasNonNull("taskId")) {
                    media.remove("mediaPath");
                    try {var view=mediaView.apply(media.path("taskId").asText());
                        if(!view.blocked()&&!view.expired()&&view.mediaPath()!=null)media.put("mediaPath",view.mediaPath());
                    }catch(BusinessException e){media.remove("artifactRef");}
                }
                continue;
            }
            if(!Set.of("approval","task").contains(type)) continue;
            Approval a=projected.get(part.path("approvalId").asText());
            if(a==null || !a.sessionId().equals(s.id())) { object.removeAll(); object.put("type","text").put("text","作品不可用"); continue; }
            TaskQuote q=quote(a);
            var context=contexts.getOrDefault(a.callId(),json.createObjectNode().put("version",0));
            object.set("sourceRef",context.get("sourceRef")); object.set("planRef",context.get("planRef")); object.putNull("stepId");
            for(var step:context.path("steps")) if(q.mediaType().equals(step.path("kind").asText()) && step.path("id").asText().equals(context.path("currentStepId").asText()))
                object.put("stepId",step.path("id").asText());
            object.put("status",a.status());
            if("approval".equals(type)) {
                object.put("version",a.version()).put("modelLabel",q.modelLabel()).put("outputType",q.mediaType())
                        .put("amount",q.amount().toPlainString()).put("currency",q.currency()).put("expiresAt",a.expires().toString())
                        .put("prompt",q.inputSnapshot().path("prompt").asText()).put("ratio",q.inputSnapshot().path("ratio").asText())
                        .put("duration",q.inputSnapshot().path("duration").asInt());
                if(q.inputSnapshot().has("megapixels")) object.set("megapixels",q.inputSnapshot().get("megapixels"));
                org.example.seedancegenarate.agent.generation.AgentReferenceProjection.apply(object,q.inputSnapshot());
            } else {
                object.put("taskId",a.taskId()).put("outputType",q.mediaType()).putNull("mediaPath").put("blocked",false).put("expired",false).put("message",a.error());
                if(a.taskId()!=null) {
                    try {
                        var view=mediaView.apply(a.taskId());
                        object.put("status",view.status()).put("outputType",view.mediaType()).put("mediaPath",view.mediaPath())
                                .put("blocked",view.blocked()).put("expired",view.expired()).put("message",view.message());
                    } catch(BusinessException e) { object.put("status","UNAVAILABLE").put("message","作品暂不可用"); }
                }
            }
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public void expire() {
        for(String id:approvals.expired()) tx.executeWithoutResult(t->{
            Approval initial=approvals.get(id); if(initial==null) return;
            Session initialSession=store.session(initial.sessionId()); if(initialSession==null) return;
            Session s=store.ownedForLifecycle(initialSession.conversationId(),initialSession.userId(),true);
            Approval a=approvals.locked(id);
            if(!"PENDING".equals(a.status()) || a.expires().isAfter(store.now())) return;
            approvals.answer(id,"EXPIRED"); store.callStatus(a.callId(),"CANCELLED",null);
            Turn turn=store.lockedTurn(a.turnId());
            if(a.turnId().equals(s.activeTurnId()) && turn.epoch()==a.epoch()) store.executionStatus(turn.id(),"FAILED","生成确认已过期，请重新发送需求");
            store.touch(s);
        });
    }
}
