package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** A grant authorizes immutable child quotes; never submits a paid domain operation itself. */
@Service @RequiredArgsConstructor @Slf4j
public class AgentBatchApplication {
    private final AgentStore store;
    private final TransactionTemplate tx;
    private final AsyncJobService jobs;
    public record Answer(String clientActionId,Integer expectedVersion,String bindingHash,String decision) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Answer fromJson(JsonNode n) {
            if(n==null||!n.isObject()||!n.path("expectedVersion").isIntegralNumber()||!n.path("expectedVersion").canConvertToInt()
                    ||!n.path("clientActionId").isTextual()||!n.path("bindingHash").isTextual()||!n.path("decision").isTextual())
                throw new IllegalArgumentException("批次确认格式无效");
            return new Answer(n.path("clientActionId").textValue(),n.path("expectedVersion").intValue(),n.path("bindingHash").textValue(),n.path("decision").textValue());
        }
    }
    public void answer(long user,long conversation,String grant,Answer a) {
        if(a==null||a.expectedVersion()==null||a.expectedVersion()<1||a.bindingHash()==null||!a.bindingHash().matches("[0-9a-f]{64}")||!Set.of("APPROVE","REJECT").contains(Objects.toString(a.decision(),"")))
            throw BusinessException.badRequest("批次确认参数无效");
        String key=AgentApplication.text(a.clientActionId(),64,"请求编号");AgentApplication.text(grant,64,"批次编号");
        String hash=AgentApplication.hashValue(store,List.of("batch",grant,a.expectedVersion(),a.bindingHash(),a.decision()));
        tx.executeWithoutResult(t->{
            var s=store.owned(conversation,user,true);String previous=store.requestHash(s,key);
            if(previous!=null){if(!previous.equals(hash))throw BusinessException.conflict("同一请求编号不能用于不同内容");return;}
            var b=store.batches().get(grant);
            if(b==null||!b.session().equals(s.id()))throw BusinessException.notFound("批次不存在");
            if(!"PENDING".equals(b.status())||b.version()!=a.expectedVersion()||!b.binding().equals(a.bindingHash())||!b.expires().isAfter(store.now())
                    ||!store.batches().current(store,s,b)||!store.batches().intact(store,b))throw BusinessException.conflict("批次已更新或过期，请刷新后重新确认");
            boolean accepted="APPROVE".equals(a.decision());store.batches().approve(store,s,b,accepted);
            String text=accepted?"确认整组生成":"取消整组生成";
            store.message(s,b.turn(),"USER",text,store.read(store.write(List.of(Map.of("type","text","text",text)))),key,hash);
            for(String id:store.batches().dispatch(store,s,grant))jobs.enqueue(AgentGenerationRuntime.JOB,id,id);
            store.touch(s);
        });
    }
    @Scheduled(fixedDelay=30000)
    public void reconcile() {
        for(String id:store.batches().due())try {tx.executeWithoutResult(t->{
            var first=store.batches().get(id);if(first==null)return;
            var initial=store.session(first.session());if(initial==null)return;
            var s=store.ownedForLifecycle(initial.conversationId(),initial.userId(),true);
            var b=store.batches().get(id);if(b==null)return;
            store.batches().expire(store,s,b);
            for(String approval:store.batches().dispatch(store,s,id))jobs.enqueue(AgentGenerationRuntime.JOB,approval,approval);
            store.touch(s);
        });}catch(Exception e){log.warn("Batch reconciliation deferred: grant={}, kind={}",id,e.getClass().getSimpleName());}
    }
}
