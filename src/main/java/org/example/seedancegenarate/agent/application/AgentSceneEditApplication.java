package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

/** Explicit scene commands; no provider or paid domain operation is performed here. */
@Service @RequiredArgsConstructor @Slf4j
public class AgentSceneEditApplication {
    private final AgentStore store;
    private final AgentApprovalStore approvals;
    private final TransactionTemplate tx;
    private final AsyncJobService jobs;
    public record Command(String clientActionId,Long expectedWorkspaceVersion,ArtifactRef reference,String instruction) {
        /** Do not silently coerce fractional/string versions into an approved artifact identity. */
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static Command fromJson(JsonNode body) {
            if(body==null||!body.isObject())throw new IllegalArgumentException("修改请求必须是对象");
            JsonNode version=body.path("expectedWorkspaceVersion"), ref=body.path("reference"), artifactVersion=ref.path("version");
            if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<0
                    ||!ref.isObject()||!artifactVersion.isIntegralNumber()||!artifactVersion.canConvertToInt()||artifactVersion.intValue()<1
                    ||!body.path("clientActionId").isTextual()||!body.path("instruction").isTextual()
                    ||!ref.path("artifactId").isTextual()||!ref.path("sceneId").isTextual())
                throw new IllegalArgumentException("修改请求字段或版本无效");
            return new Command(body.path("clientActionId").textValue(),version.longValue(),
                    new ArtifactRef(ref.path("artifactId").textValue(),artifactVersion.intValue(),ref.path("sceneId").textValue()),
                    body.path("instruction").textValue());
        }
    }

    public void apply(long user,long conversation,Command command) {
        if(command==null||command.expectedWorkspaceVersion()==null||command.expectedWorkspaceVersion()<0||command.reference()==null)
            throw BusinessException.badRequest("修改请求缺少有效版本或分镜");
        String key=AgentApplication.text(command.clientActionId(),64,"请求编号");
        String instruction=AgentApplication.text(command.instruction(),2000,"修改要求");
        ArtifactRef ref=command.reference();
        AgentApplication.text(ref.artifactId(),64,"作品编号"); AgentApplication.text(ref.sceneId(),64,"分镜编号");
        if(ref.version()<1)throw BusinessException.badRequest("作品版本无效");
        String hash=AgentApplication.hashValue(store,new Command(key,command.expectedWorkspaceVersion(),ref,instruction));
        tx.executeWithoutResult(t->{
            var s=store.owned(conversation,user,true);
            String replay=store.requestHash(s,key);
            if(replay!=null) {if(!replay.equals(hash))throw BusinessException.conflict("同一请求编号不能用于不同内容");return;}
            var w=store.workspace(s);
            if(w.path("version").asLong()!=command.expectedWorkspaceVersion())throw BusinessException.conflict("创作状态已有更新，请刷新后重新选择分镜");
            var old=store.lockedTurn(s.activeTurnId());
            if(old==null||"__DIRECT__".equals(old.channel()))throw BusinessException.conflict("当前没有可修改的逐幕计划");
            var recipe=store.recipes().latest(s);
            if(recipe!=null&&!java.util.Set.of("COMPLETED","CANCELLED").contains(recipe.status()))throw BusinessException.conflict("请先结束当前技能创作");
            store.plans().edits().prepare(store,s,ref,instruction,w);
            approvals.cancel(old.id()); store.cancel(old);
            w.put("version",w.path("version").asLong()+1); store.saveWorkspace(s,w);
            var next=store.turn(store.newTurn(s,old.channel(),s.goal()));
            String binding=store.modelBinding(old); if(binding!=null)store.bindModel(next,binding);
            String text="修改分镜「"+ref.sceneId()+"」："+instruction;
            store.message(s,next.id(),"USER",text,store.read(store.write(java.util.List.of(Map.of("type","text","text",text)))),key,hash);
            enqueue(next); store.touch(s);
        });
    }
    @Scheduled(fixedDelay=30000)
    public void reconcile() {
        for(String id:store.plans().edits().due()) {
            try {tx.executeWithoutResult(t->store.plans().edits().wake(store,id).ifPresent(this::enqueue));}
            catch(Exception e){log.warn("Scene edit recovery deferred: edit={}, kind={}",id,e.getClass().getSimpleName());}
        }
    }
    private void enqueue(AgentRows.Turn t) {
        jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(t),store.write(new AgentRuntime.Payload(t.id(),t.epoch(),t.step(),null)));
    }
}
