package org.example.seedancegenarate.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.agent.application.AgentApprovalApplication;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Paid work has an explicit durable submission boundary, independent of the conversational turn. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentGenerationRuntime {
    public static final String JOB="AGENT_GENERATION";
    private final AgentStore store;
    private final AgentApprovalStore approvals;
    private final AgentApprovalApplication approvalApp;
    private final AgentGenerationGateway gateway;
    private final AsyncJobService jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private record Work(Session session,Turn turn,Approval approval) {}
    public AgentBatchRuntime.Prepared prepareBatch(org.example.seedancegenarate.agent.model.AgentContext context,TaskQuote quote) {
        return store.batches().enabled()?AgentBatchRuntime.prepare(gateway,context,quote):null;
    }

    /** Caller owns the session lock and either a Skill job fence or the direct-command transaction. */
    public void awaitApproval(Session s,Turn t,Call call,TaskQuote quote) {
        boolean direct="DIRECT".equals(store.callContext(call.id()).path("executionMode").asText());
        if(quote==null || quote.amount()==null || quote.amount().signum()<0
                || !Set.of("IMAGE","VIDEO","AUDIO").contains(Objects.toString(quote.mediaType(),""))
                || direct!="DIRECT".equals(quote.origin()) || !direct && "AUDIO".equals(quote.mediaType()))
            throw new IllegalArgumentException("Invalid generation quote");
        String id=approvals.create(s,t,call,quote);
        store.callStatus(call.id(),"WAITING_APPROVAL",null); store.executionStatus(t.id(),"WAITING_APPROVAL",null);
        var parts=json.createArrayNode();
        parts.addObject().put("type","approval").put("approvalId",id).put("skillId",call.skillId());
        parts.addObject().put("type","task").put("approvalId",id);
        store.message(s,t.id(),"ASSISTANT","请确认生成方案和预计费用",parts,null,null);
    }
    public void execute(AsyncJob lease) {
        String id=lease.getPayload();
        try {
            Work work=tx.execute(t->begin(lease,id));
            if(work==null) return;
            if(work.approval().taskId()==null) {
                String task=gateway.findAccepted(work.session().userId(),work.approval().requestId());
                if(task==null) task=gateway.submit(work.session().userId(),approvalApp.quote(work.approval()),work.approval().requestId());
                if(task==null || task.isBlank()) throw new IllegalStateException("Missing accepted task");
                String accepted=task;
                tx.executeWithoutResult(t->bind(lease,id,accepted));
            } else {
                var view=gateway.read(work.session().userId(),work.approval().taskId());
                tx.executeWithoutResult(t->finish(lease,id,view));
            }
        } catch(GenerationRejectedException e) {
            tx.executeWithoutResult(t->reject(lease,id,e.getMessage()));
        } catch(Exception e) {
            log.warn("Agent generation awaiting reconciliation: approval={}, job={}, kind={}",id,lease.getId(),e.getClass().getSimpleName());
            tx.executeWithoutResult(t->{
                fence(lease); Work w=locked(id);
                if(w!=null && active(w.approval())) {
                    approvals.status(id,w.approval().status(),w.approval().taskId()==null?"正在核实提交结果，请勿重复生成":"正在同步任务状态，请稍后查看");
                    store.touch(w.session());
                }
                complete(lease);
            });
        }
    }
    private Work locked(String id) {
        Approval initial=approvals.get(id); if(initial==null) return null;
        Session ss=store.session(initial.sessionId()); if(ss==null) return null;
        Session s=store.ownedForLifecycle(ss.conversationId(),ss.userId(),true);
        Turn t=store.lockedTurn(initial.turnId());
        return new Work(s,t,approvals.locked(id));
    }
    private Work begin(AsyncJob lease,String id) {
        fence(lease); Work w=locked(id);
        if(w==null || !active(w.approval())) { complete(lease); return null; }
        if("APPROVED".equals(w.approval().status())) {
            if(!current(w) || !store.userExists(w.session().userId())) {
                approvals.status(id,"CANCELLED",null); store.callStatus(w.approval().callId(),"CANCELLED",null);
                if(current(w)) store.executionStatus(w.turn().id(),"FAILED","账号当前不可用，生成未提交");
                store.touch(w.session()); complete(lease); return null;
            }
            // Linearization point: cancellation after this commit stops continuation, not this generation.
            approvals.status(id,"SUBMITTING",null); store.callStatus(w.approval().callId(),"SUBMITTING",null);
            store.touch(w.session());
        }
        return new Work(w.session(),w.turn(),approvals.locked(id));
    }
    private void bind(AsyncJob lease,String id,String task) {
        fence(lease); Work w=locked(id);
        if(w==null || !"SUBMITTING".equals(w.approval().status())) { complete(lease); return; }
        approvals.bind(id,task); store.callStatus(w.approval().callId(),"WAITING_TASK",null);
        if(current(w)) store.executionStatus(w.turn().id(),"WAITING_TASK",null);
        store.touch(w.session()); complete(lease);
    }
    private void reject(AsyncJob lease,String id,String reason) {
        fence(lease); Work w=locked(id);
        if(w==null || !"SUBMITTING".equals(w.approval().status())) { complete(lease); return; }
        String message=reason==null?"生成未受理，请重新调整方案":reason.substring(0,Math.min(240,reason.length()));
        approvals.status(id,"FAILED",message); store.callStatus(w.approval().callId(),"FAILED",message);
        wakeEdit(w.session(),id);
        if(store.batches().grant(id)!=null){settleBatch(w,id,false);store.touch(w.session());complete(lease);return;}
        if(current(w)) {
            var context=store.callContext(w.approval().callId());
            boolean durable=!"DIRECT".equals(context.path("executionMode").asText()) && context.hasNonNull("executionPlanId");
            if(context.hasNonNull("sceneItemId"))store.plans().sceneFailed(context,"FAILED");
            if(durable)store.plans().observe(w.turn(),store.call(w.approval().callId()),"MEDIA_TASK_RESULT","MEDIA_SUBMISSION_REJECTED",
                    "生成提交已明确未受理；未创建作品，也未自动重投任务。");
            store.executionStatus(w.turn().id(),durable||context.hasNonNull("sceneItemId")?"SUSPENDED":"FAILED",message);
            store.message(w.session(),w.turn().id(),"ASSISTANT",message,json.valueToTree(List.of(Map.of("type","text","text",message))),null,null);
        }
        store.touch(w.session()); complete(lease);
    }
    private boolean current(Work w) {
        if(store.batches().grant(w.approval().id())!=null)return store.batches().authorized(store,w.session(),w.approval().id());
        Turn t=w.turn(); Approval a=w.approval();
        return t!=null && t.id().equals(w.session().activeTurnId()) && t.epoch()==a.epoch() && t.step()==a.step()
                && Set.of("SUBMITTING","WAITING_TASK").contains(t.status());
    }
    private boolean active(Approval a) { return a!=null && Set.of("APPROVED","SUBMITTING","ACCEPTED").contains(a.status()); }
    private void fence(AsyncJob job) { if(!jobs.renew(job,AgentRuntime.LEASE_SECONDS)) throw new IllegalStateException("Agent generation lease lost"); }
    private void complete(AsyncJob job) { if(!jobs.complete(job)) throw new IllegalStateException("Agent generation completion lease lost"); }
    @Scheduled(fixedDelay=30000)
    public void reconcile() {
        for(String id:approvals.due()) {
            try { wake(id); }
            catch(Exception e) { log.warn("Agent generation wake deferred: approval={}, kind={}",id,e.getClass().getSimpleName()); }
        }
    }
    @EventListener
    public void onTask(TaskStatusChangedEvent event) {
        if(event.message()==null) return;
        Runnable notify=()->{
            try {
                var isolated=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
                isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                isolated.executeWithoutResult(t->{ for(String id:approvals.byTask(event.message().taskId())) wake(id); });
            } catch(Exception e) { log.warn("Agent task event deferred to reconciliation: kind={}",e.getClass().getSimpleName()); }
        };
        if(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { public void afterCommit() { notify.run(); } });
        else notify.run();
    }
    private void wake(String id) {
        // Event payload is only a doorbell. Re-read writer facts inside the Worker.
        var job=jobs.find(JOB,id);
        if(job==null || Set.of("SUCCEEDED","DEAD").contains(job.getStatus())) jobs.enqueue(JOB,id,id);
    }
    private void finish(AsyncJob lease,String id,AgentGenerationGateway.TaskView view) {
        fence(lease); Work w=locked(id);
        if(w==null || !"ACCEPTED".equals(w.approval().status())) { complete(lease); return; }
        if(view==null || !w.approval().taskId().equals(view.taskId())) throw new IllegalStateException("Task identity changed");
        if(!Set.of("SUCCESS","FAILED","CANCELLED").contains(view.status())) {
            approvals.postpone(id); complete(lease); return;
        }
        var context=store.callContext(w.approval().callId());
        boolean direct="DIRECT".equals(context.path("executionMode").asText());
        boolean durable=!direct && context.hasNonNull("executionPlanId");
        boolean success="SUCCESS".equals(view.status());
        String message=success?(view.blocked()?"生成完成，但作品已屏蔽":view.expired()?"生成完成，作品已过期":"作品已生成，可在对话中查看"):
                "这次生成未能完成，请查看任务详情";
        approvals.status(id,success?"SUCCEEDED":"FAILED",success?null:message);
        store.callStatus(w.approval().callId(),success?"SUCCEEDED":"FAILED",success?null:message);
        if(success) approvals.artifact(w.session(),w.approval(),view.mediaType(),"生成作品");
        wakeEdit(w.session(),id);
        if(store.batches().grant(id)!=null){settleBatch(w,id,success);store.touch(w.session());complete(lease);return;}
        if(current(w)) {
            store.message(w.session(),w.turn().id(),"ASSISTANT",message,json.valueToTree(List.of(Map.of("type","text","text",message))),null,null);
            if(durable && success) {
                store.advance(w.session(),w.turn()).ifPresent(next->
                        jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(next),store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),null))));
            } else if(durable) {
                store.plans().sceneFailed(context,view.status());
                String code="CANCELLED".equals(view.status())?"MEDIA_TASK_CANCELLED":"MEDIA_TASK_FAILED";
                store.plans().observe(w.turn(),store.call(w.approval().callId()),"MEDIA_TASK_RESULT",code,
                        "媒体任务未成功完成；未创建作品，也未自动重投任务。");
                store.executionStatus(w.turn().id(),"SUSPENDED","媒体生成失败，已暂停计划，请检查任务详情后调整方案。");
            } else if(direct
                    || store.workspace(w.session()).path("planConfirmed").asBoolean() || w.turn().step()>=AgentRuntime.MAX_STEPS-1)
                store.executionStatus(w.turn().id(),"COMPLETED",null);
            else {
                store.advance(w.session(),w.turn()).ifPresent(next->
                        jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(next),store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),null))));
            }
        }
        store.touch(w.session()); complete(lease);
    }
    private void wakeEdit(Session s,String approvalId) {
        store.plans().edits().taskSettled(store,s,approvalId).ifPresent(next->
                jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(next),store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),null))));
    }
    private void settleBatch(Work w,String approval,boolean success) {
        store.batches().settled(store,w.session(),approval,success).ifPresent(next->
                jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(next),store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),null))));
        for(String id:store.batches().dispatch(store,w.session(),store.batches().grant(approval)))jobs.enqueue(JOB,id,id);
    }
}
