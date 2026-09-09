package org.example.seedancegenarate.agent.recipe;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

@Service
@RequiredArgsConstructor
public class AgentRecipeApplication {
    private final AgentStore store;
    private final RecipeCatalog catalog;
    private final AgentModelGateway models;
    private final AsyncJobService jobs;
    private final AgentApprovalStore approvals;
    private final TransactionTemplate tx;
    public record Start(String clientActionId,Long expectedRevision,String recipeVersionId,String content) {}
    public record Reference(String artifactId,int version) {}
    public record Command(String clientActionId,Long expectedVersion,String action,Map<String,String> values,Reference reference) {}
    public void start(long user,long conversation,Start request) {
        if(request==null||request.expectedRevision()==null||request.expectedRevision()<0)throw BusinessException.badRequest("缺少对话版本");
        String key=AgentApplication.text(request.clientActionId(),64,"请求编号");
        AgentApplication.text(request.recipeVersionId(),64,"技能版本");
        String content=request.content()==null?null:AgentApplication.text(request.content(),4000,"创作目标");
        String hash=AgentApplication.hashValue(store,request);var initial=store.owned(conversation,user,false);
        if(replayed(initial,key,hash))return;
        var version=catalog.requireRunnable(user,request.recipeVersionId());
        if(!version.definition().requiredInputs().isEmpty()||version.definition().stages().stream().anyMatch(s->s.outputType()!=null&&!Set.of("SCRIPT","STORYBOARD","PROMPT").contains(s.outputType())))
            throw BusinessException.badRequest("本批技能运行仅支持文本策划；参考素材、媒体生成与合成阶段尚未开放");
        String channel=models.defaultChannel();
        tx.executeWithoutResult(t->{
            var s=store.owned(conversation,user,true);if(replayed(s,key,hash))return;
            if(s.revision()!=request.expectedRevision())throw BusinessException.conflict("对话已更新，请刷新后使用技能");
            catalog.requireRunnable(user,request.recipeVersionId());
            var active=store.turn(s.activeTurnId());var run=store.recipes().latest(s);
            if(active!=null&&(AgentApplication.busy(active.status())||Set.of("WAITING_USER","SUSPENDED").contains(active.status()))
                    ||run!=null&&!Set.of("COMPLETED","CANCELLED").contains(run.status())||store.workspace(s).path("planConfirmed").asBoolean())
                throw BusinessException.conflict("请先停止当前运行和已采用计划，再使用另一技能");
            String goal=content==null?"请根据本次技能收集并确认创作目标":content;
            var workspace=store.workspace(s);workspace.putNull("planRef").putNull("selection").putNull("currentStepId").put("planConfirmed",false).put("version",workspace.path("version").asLong()+1);workspace.putArray("steps");store.saveWorkspace(s,workspace);
            Turn turn=store.turn(store.newTurn(s,channel,goal));store.recipes().create(s,version.id(),turn,goal);
            String text="使用技能「"+version.name()+"」第 "+version.version()+" 版"+(content==null?"":"："+content);
            message(s,turn,text,key,hash);enqueue(turn);store.touch(s);
        });
    }
    public void command(long user,long conversation,String runId,Command request) {
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<1||!Set.of("ANSWER","APPROVE_ARTIFACT","RESUME","STOP").contains(Objects.toString(request.action(),"")))
            throw BusinessException.badRequest("技能运行操作无效");
        String key=AgentApplication.text(request.clientActionId(),64,"请求编号");AgentApplication.text(runId,64,"运行编号");
        if(!"ANSWER".equals(request.action())&&request.values()!=null||!"APPROVE_ARTIFACT".equals(request.action())&&request.reference()!=null)
            throw BusinessException.badRequest("操作包含不适用的字段");
        if("APPROVE_ARTIFACT".equals(request.action())&&(request.reference()==null||request.reference().version()<1))throw BusinessException.badRequest("缺少作品精确版本");
        if(request.reference()!=null)AgentApplication.text(request.reference().artifactId(),64,"作品编号");
        String hash=AgentApplication.hashValue(store,List.of(runId,request));
        tx.executeWithoutResult(txStatus->{
            var s=store.owned(conversation,user,true);if(replayed(s,key,hash))return;
            var r=store.recipes().latest(s);
            if(r==null||!r.id().equals(runId))throw BusinessException.notFound("技能运行不存在");
            if(r.version()!=request.expectedVersion())throw BusinessException.conflict("技能状态已更新，请刷新后操作");
            var turn=store.lockedTurn(r.turnId());
            if("STOP".equals(request.action())) {
                store.recipes().stop(s,true);
                if(turn!=null&&turn.id().equals(s.activeTurnId())) {approvals.cancel(turn.id());store.cancel(turn);}
                var w=store.workspace(s);
                if(turn!=null&&turn.id().equals(s.activeTurnId())) {w.putNull("planRef").putNull("currentStepId").put("planConfirmed",false).put("version",w.path("version").asLong()+1);w.putArray("steps");store.saveWorkspace(s,w);}
            } else {
                if(turn==null||!turn.id().equals(s.activeTurnId()))throw BusinessException.conflict("当前对话运行已改变，请刷新");
                if(Set.of("COMPLETED","CANCELLED","WAITING_PLAN").contains(r.status())||AgentApplication.busy(turn.status()))throw BusinessException.conflict("当前技能状态不接受此操作");
                switch(request.action()) {
                    case "ANSWER" -> store.recipes().answer(store,s,r,request.values());
                    case "APPROVE_ARTIFACT" -> store.recipes().approve(store,s,r,request.reference().artifactId(),request.reference().version());
                    case "RESUME" -> {if(!Set.of("FAILED","SUSPENDED").contains(r.status()))throw BusinessException.conflict("当前技能无需恢复");}
                }
                if("CANCELLED".equals(turn.status())) {
                    store.recipes().human(turn);
                    turn=store.turn(store.newTurn(s,turn.channel(),s.goal()));store.recipes().bind(s,turn);
                } else store.resumeHuman(turn);
                enqueue(store.turn(turn.id()));
            }
            String text=switch(request.action()){case "ANSWER"->"已确认技能信息："+store.write(request.values());case "APPROVE_ARTIFACT"->"已确认作品「"+request.reference().artifactId()+"」第 "+request.reference().version()+" 版";case "STOP"->"已停止技能运行，已有作品保留";default->"恢复技能运行";};
            message(s,turn,text,key,hash);store.touch(s);
        });
    }
    private boolean replayed(Session s,String key,String hash) {String old=store.requestHash(s,key);if(old==null)return false;if(!old.equals(hash))throw BusinessException.conflict("同一请求编号不能用于不同内容");return true;}
    private void message(Session s,Turn t,String text,String key,String hash){store.message(s,t==null?null:t.id(),"USER",text,store.read(store.write(List.of(Map.of("type","text","text",text)))),key,hash);}
    private void enqueue(Turn t){jobs.enqueue(AgentRuntime.STEP_JOB,AgentRuntime.jobKey(t),store.write(new AgentRuntime.Payload(t.id(),t.epoch(),t.step(),null)));}
}
