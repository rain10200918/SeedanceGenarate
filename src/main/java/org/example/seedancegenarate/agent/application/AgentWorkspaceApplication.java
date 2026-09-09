package org.example.seedancegenarate.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Explicit adoption starts bounded planning, never bypasses paid approval. */
@Service
@RequiredArgsConstructor
public class AgentWorkspaceApplication {
    private final AgentStore store;
    private final AgentApprovalStore approvals;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AsyncJobService jobs;
    private final AgentModelGateway models;
    public record Command(String clientActionId,Long expectedVersion,String action,ArtifactRef reference) {}

    public void apply(long user,long conversation,Command request) {
        if(request==null || request.expectedVersion()==null || request.expectedVersion()<0
                || !Set.of("SELECT","ADOPT_PLAN","STOP_PLAN","GENERATE_SCENES_IMAGE","GENERATE_SCENES_VIDEO").contains(Objects.toString(request.action(),"")))
            throw BusinessException.badRequest("工作区操作无效");
        String key=AgentApplication.text(request.clientActionId(),64,"请求编号");
        var ref=request.reference();
        boolean sceneStart=request.action().startsWith("GENERATE_SCENES_");
        if(ref!=null) {
            AgentApplication.text(ref.artifactId(),64,"作品编号");
            if(ref.version()<1) throw BusinessException.badRequest("作品版本无效");
            if(ref.sceneId()!=null) AgentApplication.text(ref.sceneId(),64,"分镜编号");
        }
        if(("ADOPT_PLAN".equals(request.action()) && ref==null) || ("STOP_PLAN".equals(request.action()) && ref!=null))
            throw BusinessException.badRequest("计划操作引用无效");
        if(sceneStart&&(ref==null||ref.sceneId()!=null))throw BusinessException.badRequest("逐幕生成必须选择完整分镜的准确版本");
        String hash=AgentApplication.hashValue(store,request);
        var initial=store.owned(conversation,user,false);
        String replay=store.requestHash(initial,key);
        if(replay!=null) { if(!replay.equals(hash)) throw BusinessException.conflict("同一请求编号不能用于不同内容"); return; }
        String channel="ADOPT_PLAN".equals(request.action())||sceneStart?models.defaultChannel():null;
        tx.executeWithoutResult(t->{
            var s=store.owned(conversation,user,true);
            String old=store.requestHash(s,key);
            if(old!=null) { if(!old.equals(hash)) throw BusinessException.conflict("同一请求编号不能用于不同内容"); return; }
            var w=store.workspace(s);
            if(w.path("version").asLong()!=request.expectedVersion()) throw BusinessException.conflict("创作状态已有更新，请刷新后操作");
            var artifact=ref==null?null:store.artifactVersion(s,ref.artifactId(),ref.version());
            var turn=store.lockedTurn(s.activeTurnId());
            if(sceneStart) {
                if(turn!=null&&AgentApplication.busy(turn.status()))throw BusinessException.conflict("当前任务仍在执行或等待费用确认，请先处理当前任务");
                var recipe=store.recipes().latest(s);
                if(recipe!=null&&!Set.of("COMPLETED","CANCELLED").contains(recipe.status()))throw BusinessException.conflict("请先结束当前技能创作再启动逐幕生成");
                if(!"STORYBOARD".equals(artifact.type()))throw BusinessException.badRequest("逐幕生成仅接受分镜作品");
            }
            if(ref!=null && ref.sceneId()!=null) {
                boolean found=false;
                if("STORYBOARD".equals(artifact.type()) && artifact.data()!=null)
                    for(var scene:artifact.data().path("scenes")) if(ref.sceneId().equals(scene.path("sceneId").asText())) found=true;
                if(!found) throw BusinessException.badRequest("请选择该作品中存在的分镜");
            }
            String text;
            switch(request.action()) {
                case "GENERATE_SCENES_IMAGE","GENERATE_SCENES_VIDEO" -> text="已启动整份分镜逐幕生成，沿用本版已有成功作品，未完成幕按实际批准单确认费用。";
                case "SELECT" -> { w.set("selection",json.valueToTree(ref)); text=ref==null?"已清除作品引用":"已选择作品「"+artifact.title()+"」第 "+ref.version()+" 版"+(ref.sceneId()==null?"":"，分镜 "+ref.sceneId()); }
                case "ADOPT_PLAN" -> {
                    var active=w.path("planRef");
                    if(!"PLAN".equals(artifact.type()) || ref.sceneId()!=null || !ref.artifactId().equals(active.path("artifactId").asText())
                            || ref.version()!=active.path("version").asInt() || !store.latestArtifact(s,ref.artifactId(),ref.version()))
                        throw BusinessException.conflict("请选择当前最新的创作计划");
                    w.put("planConfirmed",true);
                    text="已采用创作计划「"+artifact.title()+"」";
                }
                default -> { w.putNull("planRef").put("planConfirmed",false).putNull("currentStepId"); w.putArray("steps"); text="已停止当前创作计划，历史作品保留"; }
            }
            if(turn!=null && (AgentApplication.busy(turn.status()) || Set.of("WAITING_USER","SUSPENDED").contains(turn.status()))) { approvals.cancel(turn.id()); store.cancel(turn); }
            if(sceneStart)artifact=store.plans().startScenes(store,s,artifact,request.action().endsWith("IMAGE")?"IMAGE":"VIDEO",w);
            if("ADOPT_PLAN".equals(request.action())) store.plans().adopt(s,artifact,w);
            else store.recipes().stop(s,"STOP_PLAN".equals(request.action()));
            w.put("version",w.path("version").asLong()+1); store.saveWorkspace(s,w);
            if("ADOPT_PLAN".equals(request.action())) store.recipes().adopt(store,s,artifact);
            store.message(s,turn==null?null:turn.id(),"USER",text,json.valueToTree(List.of(Map.of("type","text","text",text))),key,hash);
            if("ADOPT_PLAN".equals(request.action())||sceneStart) {
                var next=store.turn(store.newTurn(s,channel,artifact.data().path("goal").asText()));
                store.recipes().bind(s,next);
                jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(next),store.write(new AgentRuntime.Payload(next.id(),next.epoch(),next.step(),null)));
            }
            store.touch(s);
        });
    }
}
