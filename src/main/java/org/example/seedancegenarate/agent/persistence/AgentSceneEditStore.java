package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.example.seedancegenarate.agent.api.AgentViews.Artifact;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Session-locked local replan. Old execution rows are never reused by the replacement. */
public final class AgentSceneEditStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final org.example.seedancegenarate.config.AgentRuntimeProperties limits;
    AgentSceneEditStore(JdbcTemplate db,ObjectMapper json,org.example.seedancegenarate.config.AgentRuntimeProperties limits){this.db=db;this.json=json;this.limits=limits;}
    private JsonNode read(String value){try{return value==null?json.nullNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String id(){return UUID.randomUUID().toString();}
    private record Edit(String id,String session,String plan,String step,JsonNode ref,String instruction,JsonNode waits,String status,String reason){}
    private Edit edit(String id){
        var rows=db.query("SELECT * FROM agent_scene_edit WHERE id=?",(r,n)->new Edit(r.getString("id"),r.getString("session_id"),r.getString("plan_id"),r.getString("edit_step_key"),read(r.getString("reference_json")),r.getString("instruction"),read(r.getString("waits_json")),r.getString("status"),r.getString("reason")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Edit active(ObjectNode w){
        Edit e=w.hasNonNull("sceneEditId")?edit(w.path("sceneEditId").asText()):null;
        return e!=null&&e.plan().equals(w.path("executionPlanId").asText())?e:null;
    }
    private JsonNode baseline(Session s,ObjectNode w) {
        if(!w.hasNonNull("executionPlanId")||!w.path("planConfirmed").asBoolean()||"CANCELLED".equals(w.path("executionStatus").asText())||w.path("steps").size()>=limits.getMaxPlanSteps())return null;
        if(db.queryForObject("SELECT COUNT(*) FROM agent_recipe_run WHERE session_id=? AND status NOT IN ('COMPLETED','CANCELLED')",Long.class,s.id())>0)return null;
        JsonNode ref=null;int media=0;boolean seenMedia=false;Set<String> kinds=new HashSet<>();
        for(var step:w.path("steps")) {
            if("STORYBOARD_SCENES".equals(step.path("scope").asText())) {
                seenMedia=true;media++;
                if(!Set.of("IMAGE","VIDEO").contains(step.path("kind").asText())||!kinds.add(step.path("kind").asText()))return null;
                if(step.path("scenes").isEmpty()) {
                    JsonNode source=null;
                    for(var previous:w.path("steps"))if(previous.path("id").equals(step.path("sourceStepId"))&&"STORYBOARD".equals(previous.path("kind").asText()))source=previous.get("artifactRef");
                    if(source==null)return null;
                    if(ref==null)ref=source;
                    if(!ref.path("artifactId").equals(source.path("artifactId")))return null;
                    if(source.path("version").asInt()>ref.path("version").asInt())ref=source;
                }
                for(var scene:step.path("scenes")) {
                    var source=scene.path("sourceRef");
                    if(ref==null)ref=source;
                    if(!ref.path("artifactId").equals(source.path("artifactId")))return null;
                    if(source.path("version").asInt()>ref.path("version").asInt())ref=source;
                }
            } else if(seenMedia||!Set.of("SUCCEEDED","SKIPPED").contains(step.path("status").asText()))return null;
        }
        if(media==0||media>2||ref==null)return null;
        String artifact=ref.path("artifactId").asText();
        var versions=db.query("SELECT MAX(version_no) FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND type='STORYBOARD'",(r,n)->r.getInt(1),s.id(),s.userId(),artifact);
        if(versions.isEmpty()||versions.get(0)!=ref.path("version").asInt())return null;
        return json.createObjectNode().put("artifactId",artifact).put("version",versions.get(0));
    }
    public void project(Session s,ObjectNode w) {
        w.remove("sceneEdit");
        Edit e=active(w);
        if(e!=null&&e.session().equals(s.id())&&e.plan().equals(w.path("executionPlanId").asText())) {
            String state=e.status();
            if(!"COMPLETED".equals(state)&&Set.of("CANCELLED","SUSPENDED","FAILED").contains(w.path("executionStatus").asText()))state="SUSPENDED";
            w.putObject("sceneEdit").put("status",state).put("reason",e.reason()).set("reference",e.ref());
            if(!"COMPLETED".equals(e.status()))return;
        }
        JsonNode ref=baseline(s,w);if(ref==null)return;
        for(var step:w.path("steps"))for(var scene:step.path("scenes")) {
            var exact=(ObjectNode)ref.deepCopy();exact.put("sceneId",scene.path("sceneId").asText());
            ((ObjectNode)scene).set("editReference",exact);
        }
    }
    public void prepare(AgentStore store,Session s,AgentContext.ArtifactRef reference,String instruction,ObjectNode w) {
        Edit existing=active(w);
        if(existing!=null&&!"COMPLETED".equals(existing.status()))throw BusinessException.conflict("当前修改尚未结束，请先处理或停止当前修改");
        JsonNode base=baseline(s,w),ref=json.valueToTree(reference);
        if(base==null||!base.path("artifactId").equals(ref.path("artifactId"))||!base.path("version").equals(ref.path("version")))
            throw BusinessException.conflict("当前计划不支持此版本的局部修改，请重新选择可修改的分镜");
        Artifact board=store.artifactVersion(s,reference.artifactId(),reference.version());
        boolean found=false;for(var scene:board.data().path("scenes"))if(reference.sceneId().equals(scene.path("sceneId").asText()))found=true;
        if(!found)throw BusinessException.badRequest("请选择确实存在的分镜");
        String oldPlan=w.path("executionPlanId").asText(),plan=id(),edit=id(),editStep="edit_"+edit.substring(0,8);
        var oldSteps=w.path("steps").deepCopy(); // frozen snapshot must not be changed while cloning
        var waits=json.createArrayNode();
        for(var step:oldSteps)for(var scene:step.path("scenes")) {
            if(reference.sceneId().equals(scene.path("sceneId").asText()))continue;
            db.query("SELECT a.id FROM agent_approval a JOIN agent_plan_scene c ON c.skill_call_id=a.call_id WHERE c.id=? AND a.session_id=? AND a.status IN ('SUBMITTING','ACCEPTED')",r->{
                waits.addObject().put("approvalId",r.getString(1)).put("stepKey",step.path("id").asText()).put("sceneId",scene.path("sceneId").asText()).set("source",scene.path("sourceRef"));
            },scene.path("id").asText(),s.id());
        }
        Artifact oldArtifact=store.artifactVersion(s,w.path("planRef").path("artifactId").asText(),w.path("planRef").path("version").asInt());
        Artifact artifact=store.artifact(s,"scene-edit-plan:"+edit,null,"PLAN",oldArtifact.title(),"修改指定分镜后继续原计划；其他成功作品保留，新媒体仍需确认费用。");
        ObjectNode data=oldArtifact.data().deepCopy();ArrayNode dataSteps=data.putArray("steps");
        dataSteps.addObject().put("id",editStep).put("kind","STORYBOARD").put("title","修改分镜 "+reference.sceneId());
        db.update("INSERT INTO agent_plan(id,session_id,artifact_id,artifact_version,status) VALUES(?,?,?,1,'READY')",plan,s.id(),artifact.id());
        db.update("INSERT INTO agent_plan_step(id,plan_id,step_key,ordinal_no,kind,title,depends_on,status) VALUES(?,?,?,0,'STORYBOARD',?,'[]','READY')",id(),plan,editStep,"修改分镜 "+reference.sceneId());
        int ordinal=1;String previous=editStep;
        for(var step:oldSteps) {
            String stepId=id(),key=step.path("id").asText();boolean scoped="STORYBOARD_SCENES".equals(step.path("scope").asText());
            String state=scoped?"PENDING":step.path("status").asText();
            db.update("INSERT INTO agent_plan_step(id,plan_id,step_key,ordinal_no,kind,title,depends_on,status,result_ref,scope,source_step_key,source_ref) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    stepId,plan,key,ordinal++,step.path("kind").asText(),step.path("title").asText(),json.createArrayNode().add(previous).toString(),state,
                    step.hasNonNull("artifactRef")?step.path("artifactRef").toString():null,scoped?"STORYBOARD_SCENES":"SINGLE",scoped?step.path("sourceStepId").asText(null):null,scoped?base.toString():null);
            var definition=dataSteps.addObject().put("id",key).put("kind",step.path("kind").asText()).put("title",step.path("title").asText());
            if(scoped){definition.put("scope","STORYBOARD_SCENES").set("sourceRef",base);}
            previous=key;
            JsonNode sceneRows=step.path("scenes");
            if(scoped&&sceneRows.isEmpty()) {
                ArrayNode initial=json.createArrayNode();int no=0;
                for(var scene:board.data().path("scenes")) {
                    var source=(ObjectNode)base.deepCopy();source.put("sceneId",scene.path("sceneId").asText());
                    initial.addObject().put("sceneId",scene.path("sceneId").asText()).put("ordinal",++no).put("status","PENDING").set("sourceRef",source);
                }
                sceneRows=initial;
            }
            for(var scene:sceneRows) {
                boolean target=reference.sceneId().equals(scene.path("sceneId").asText());
                String sceneState=target?"PENDING":Set.of("SUCCEEDED","FAILED","CANCELLED").contains(scene.path("status").asText())?scene.path("status").asText():"PENDING";
                db.update("INSERT INTO agent_plan_scene(id,plan_step_id,scene_key,ordinal_no,status,source_ref,result_ref) VALUES(?,?,?,?,?,?,?)",id(),stepId,scene.path("sceneId").asText(),scene.path("ordinal").asInt(),sceneState,scene.path("sourceRef").toString(),
                        !target&&scene.hasNonNull("artifactRef")?scene.path("artifactRef").toString():null);
            }
        }
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE session_id=? AND artifact_id=? AND version_no=1",data.toString(),s.id(),artifact.id());
        db.update("INSERT INTO agent_scene_edit(id,session_id,plan_id,old_plan_id,edit_step_key,reference_json,instruction,waits_json,status) VALUES(?,?,?,?,?,?,?,?,?)",edit,s.id(),plan,oldPlan,editStep,ref.toString(),instruction,waits.toString(),waits.isEmpty()?"EDITING":"WAITING_TASK");
        w.set("planRef",json.createObjectNode().put("artifactId",artifact.id()).put("version",1));w.put("planConfirmed",true).put("sceneEditId",edit);w.set("selection",ref);
    }
    /** Called before any model budget/deadline check; waiting itself spends no model attempt. */
    public boolean beforeStep(AgentStore store,Session s,Turn t) {
        ObjectNode w=store.workspace(s);Edit e=active(w);
        if(e==null||!e.plan().equals(w.path("executionPlanId").asText())||"COMPLETED".equals(e.status()))return true;
        if(!t.id().equals(s.activeTurnId())||!currentPlan(t,e))return false;
        if(!collect(store,s,e)) {store.touch(s);return false;}
        return true;
    }
    private boolean currentPlan(Turn t,Edit e) {
        return db.queryForObject("SELECT COUNT(*) FROM agent_plan WHERE id=? AND turn_id=? AND execution_epoch=? AND status<>'CANCELLED'",Long.class,e.plan(),t.id(),t.epoch())==1;
    }
    private boolean collect(AgentStore store,Session s,Edit e) {
        for(var wait:e.waits()) {
            var approvals=db.query("SELECT a.status,a.task_id,a.call_id FROM agent_approval a WHERE a.id=? AND a.session_id=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)},wait.path("approvalId").asText(),s.id());
            if(approvals.isEmpty())return suspend(store,s,e,"旧任务关联异常，修改已暂停，请检查任务详情。");
            var approval=approvals.get(0);
            if(Set.of("SUBMITTING","ACCEPTED").contains(approval[0])) {
                state(e,"WAITING_TASK","正在等待未修改的在途分镜收尾；不会启动重复生成。");
                store.executionStatus(s.activeTurnId(),"WAITING_TASK",null);return false;
            }
            if(!"SUCCEEDED".equals(approval[0])||approval[1]==null) {
                db.update("UPDATE agent_plan_scene SET status='FAILED',updated_at=NOW() WHERE plan_step_id=(SELECT id FROM agent_plan_step WHERE plan_id=? AND step_key=?) AND scene_key=? AND status<>'SUCCEEDED'",e.plan(),wait.path("stepKey").asText(),wait.path("sceneId").asText());
                return suspend(store,s,e,"未修改的旧任务未成功，进度已保存；请先处理该任务，不会自动重复生成。");
            }
            JsonNode context=store.callContext(approval[2]);
            if(!wait.path("source").equals(context.path("sourceRef")))return suspend(store,s,e,"旧任务来源不一致，修改已暂停。");
            var artifacts=db.query("SELECT artifact_id,version_no,type,source_ref_json FROM agent_artifact_version WHERE session_id=? AND user_id=? AND task_id=?",(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},s.id(),s.userId(),approval[1]);
            if(artifacts.size()!=1||!wait.path("source").equals(read(artifacts.get(0)[3])))return suspend(store,s,e,"旧任务作品尚未正确关联，修改已暂停。");
            var artifact=artifacts.get(0);
            var target=db.query("SELECT c.id,p.kind FROM agent_plan_scene c JOIN agent_plan_step p ON p.id=c.plan_step_id WHERE p.plan_id=? AND p.step_key=? AND c.scene_key=?",(r,n)->new String[]{r.getString(1),r.getString(2)},e.plan(),wait.path("stepKey").asText(),wait.path("sceneId").asText());
            if(target.size()!=1||!artifact[2].equals(target.get(0)[1]))return suspend(store,s,e,"旧任务类型不一致，修改已暂停。");
            db.update("UPDATE agent_plan_scene SET status='SUCCEEDED',result_ref=?,updated_at=NOW() WHERE id=?",json.createObjectNode().put("artifactId",artifact[0]).put("version",Integer.parseInt(artifact[1])).toString(),target.get(0)[0]);
        }
        Long failed=db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene c JOIN agent_plan_step p ON p.id=c.plan_step_id WHERE p.plan_id=? AND c.status IN ('FAILED','CANCELLED')",Long.class,e.plan());
        if(failed>0)return suspend(store,s,e,"未修改的分镜存在失败任务，修改已暂停；不会自动重复生成。");
        state(e,"EDITING",null);return true;
    }
    private boolean suspend(AgentStore store,Session s,Edit e,String reason) {state(e,"SUSPENDED",reason);store.executionStatus(s.activeTurnId(),"SUSPENDED",reason);return false;}
    private void state(Edit e,String status,String reason){db.update("UPDATE agent_scene_edit SET status=?,reason=?,updated_at=NOW() WHERE id=?",status,reason,e.id());}
    public List<String> due(){return db.query("SELECT e.id FROM agent_scene_edit e JOIN agent_plan p ON p.id=e.plan_id JOIN agent_session s ON s.id=e.session_id JOIN agent_turn t ON t.id=s.active_turn_id JOIN conversation c ON c.id=s.conversation_id WHERE e.status='WAITING_TASK' AND c.archived=0 AND p.turn_id=t.id AND p.execution_epoch=t.epoch AND p.status<>'CANCELLED' AND t.status='WAITING_TASK' ORDER BY e.updated_at,e.id LIMIT 50",(r,n)->r.getString(1));}
    /** Re-read current ownership/epoch after a doorbell. Never revive canceled/archived/another plan. */
    public Optional<Turn> wake(AgentStore store,String editId) {
        Edit e=edit(editId);if(e==null||!"WAITING_TASK".equals(e.status()))return Optional.empty();
        Session initial=store.session(e.session());if(initial==null)return Optional.empty();
        Session s;
        try{s=store.owned(initial.conversationId(),initial.userId(),true);}catch(BusinessException ex){return Optional.empty();}
        e=edit(editId);if(e==null||!"WAITING_TASK".equals(e.status())||!e.session().equals(s.id()))return Optional.empty();
        Turn t=store.lockedTurn(s.activeTurnId());
        if(t==null||!"WAITING_TASK".equals(t.status())||!currentPlan(t,e))return Optional.empty();
        if(!Objects.equals(store.workspace(s).path("sceneEditId").asText(),e.id()))return Optional.empty();
        if(!collect(store,s,e)){store.touch(s);return Optional.empty();}
        store.renewModelDeadline(t);store.executionStatus(t.id(),"QUEUED",null);store.touch(s);return Optional.of(store.turn(t.id()));
    }
    public Optional<Turn> taskSettled(AgentStore store,Session s,String approvalId) {
        var w=store.workspace(s);Edit e=active(w);if(e==null)return Optional.empty();
        for(var wait:e.waits())if(approvalId.equals(wait.path("approvalId").asText()))return wake(store,e.id());
        return Optional.empty();
    }
    /** The user selected the exact command, so do not ask Planner to guess it again. */
    public AgentDecision decision(AgentStore store,Session s,Turn t) {
        var w=store.workspace(s);Edit e=active(w);
        if(e==null||!currentPlan(t,e)||!"EDITING".equals(e.status())||!e.step().equals(w.path("currentStepId").asText()))return null;
        var input=json.createObjectNode().put("instruction",e.instruction());input.set("source",e.ref());
        return new AgentDecision("CALL_SKILL","正在修改指定分镜，其他已完成作品保留。",null,List.of(),"storyboard-generation",input);
    }
    public boolean permits(JsonNode context,JsonNode source) {
        if(!context.hasNonNull("sceneEditId"))return false;
        Edit e=edit(context.path("sceneEditId").asText());
        return e!=null&&"EDITING".equals(e.status())&&e.plan().equals(context.path("executionPlanId").asText())
                &&e.step().equals(context.path("currentStepId").asText())&&e.ref().equals(source);
    }
    public boolean finishStoryboard(JsonNode context,Artifact artifact) {
        if(!permits(context,json.valueToTree(artifact.sourceRef()))||!"STORYBOARD".equals(artifact.type()))return false;
        Edit e=edit(context.path("sceneEditId").asText());
        if(!artifact.id().equals(e.ref().path("artifactId").asText())||artifact.version()!=e.ref().path("version").asInt()+1)throw BusinessException.conflict("分镜版本不一致");
        var base=json.createObjectNode().put("artifactId",artifact.id()).put("version",artifact.version());
        db.update("UPDATE agent_plan_step SET status='SUCCEEDED',result_ref=?,error_code=NULL WHERE plan_id=? AND step_key=?",base.toString(),e.plan(),e.step());
        var steps=db.query("SELECT id FROM agent_plan_step WHERE plan_id=? AND scope='STORYBOARD_SCENES'",(r,n)->r.getString(1),e.plan());
        for(String step:steps) {
            db.update("UPDATE agent_plan_step SET source_ref=? WHERE id=?",base.toString(),step);
            db.query("SELECT id,scene_key FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED'",r->{
                ObjectNode ref=base.deepCopy();ref.put("sceneId",r.getString(2));
                db.update("UPDATE agent_plan_scene SET source_ref=? WHERE id=?",ref.toString(),r.getString(1));
            },step);
            var pending=db.query("SELECT id FROM agent_plan_scene WHERE plan_step_id=? AND status='PENDING' ORDER BY ordinal_no LIMIT 1",(r,n)->r.getString(1),step);
            if(!pending.isEmpty())db.update("UPDATE agent_plan_scene SET status='READY' WHERE id=?",pending.get(0));
            Long remaining=db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED'",Long.class,step);
            if(remaining==0)db.update("UPDATE agent_plan_step SET status='SUCCEEDED' WHERE id=?",step);
        }
        state(e,"COMPLETED",null);return true;
    }
}
