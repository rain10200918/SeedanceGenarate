package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Caller holds the existing session transaction for capture/confirm. No provider or pricing calls. */
public final class AgentRepairStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    AgentRepairStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public void capture(AgentStore store,Session s,Turn t,Call call,VideoPreparationException failure) {
        if(call==null||failure.repairInput()==null||!Set.of("video-generation","storyboard-generation").contains(call.skillId()))return;
        var context=(ObjectNode)store.callContext(call.id());
        var w=store.workspace(s);JsonNode step=null;
        for(var value:w.path("steps"))if(value.path("id").equals(context.path("currentStepId")))step=value;
        if(step==null||!Set.of("VIDEO","STORYBOARD").contains(step.path("kind").asText()))return;
        var target=json.createObjectNode().put("executionStepId",step.path("executionStepId").asText()).put("kind",step.path("kind").asText());
        JsonNode source=failure.sourceRef()!=null?failure.sourceRef():context.get("sourceRef");
        if("VIDEO".equals(step.path("kind").asText())&&failure.sceneOrdinal()!=null) {
            source=null;
            for(var scene:step.path("scenes"))if(scene.path("ordinal").asInt()==failure.sceneOrdinal())source=scene.get("sourceRef");
            if(source==null)return;
            target.put("sceneOrdinal",failure.sceneOrdinal());
        }
        if(source!=null&&!source.isNull()) {
            var artifact=store.artifactVersion(s,source.path("artifactId").asText(),source.path("version").asInt());
            if("STORYBOARD".equals(step.path("kind").asText())&&(!"SCRIPT".equals(artifact.type())||source.hasNonNull("sceneId")))return;
            target.set("sourceRef",source);
            if("VIDEO".equals(step.path("kind").asText()))for(var scene:step.path("scenes"))
                if(source.equals(scene.path("sourceRef")))target.set("sceneOrdinal",scene.path("ordinal"));
        } else if("STORYBOARD".equals(step.path("kind").asText()))return;
        var record=json.createObjectNode().put("failureId",UUID.randomUUID().toString()).put("callId",call.id())
                .put("code",failure.code()).put("message",failure.getMessage()).put("workspaceVersion",w.path("version").asLong());
        record.set("target",target);record.set("input",failure.repairInput());
        context.set("localRepairFailure",record);
        db.update("UPDATE agent_skill_call SET context_json=? WHERE id=?",store.write(context),call.id());
    }
    public JsonNode current(AgentStore store,Session s) {
        var t=store.turn(s.activeTurnId());if(t==null||!"SUSPENDED".equals(t.status()))return null;
        var calls=db.query("SELECT id FROM agent_skill_call WHERE turn_id=? AND epoch=? AND step_no=? AND status='SUSPENDED' ORDER BY created_at DESC",
                (r,n)->r.getString(1),t.id(),t.epoch(),t.step());
        if(calls.isEmpty())return null;
        var c=store.callContext(calls.get(0));var f=c.get("localRepairFailure");var w=store.workspace(s);
        if(f==null||f.path("workspaceVersion").asLong()!=w.path("version").asLong()||!c.path("executionPlanId").equals(w.path("executionPlanId")))return null;
        JsonNode step=null;
        for(var value:w.path("steps"))if(value.path("executionStepId").equals(f.path("target").path("executionStepId")))step=value;
        if(step==null||!step.path("id").equals(w.path("currentStepId"))||Set.of("SUCCEEDED","SKIPPED","CANCELLED").contains(step.path("status").asText()))return null;
        if(db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE call_id=?",Long.class,calls.get(0))>0)return null;
        // Any non-successful paid sibling blocks reopening this batch, including unknown acceptance.
        if(db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene s JOIN agent_approval a ON a.call_id=s.skill_call_id WHERE s.plan_step_id=? AND s.status<>'SUCCEEDED'",Long.class,step.path("executionStepId").asText())>0)return null;
        if(db.queryForObject("SELECT COUNT(*) FROM agent_generation_batch WHERE parent_call_id=?",Long.class,calls.get(0))>0)return null;
        var paidSources=db.query("SELECT c.context_json FROM agent_approval a JOIN agent_skill_call c ON c.id=a.call_id WHERE a.session_id=? AND (a.task_id IS NOT NULL OR a.status IN ('SUBMITTING','ACCEPTED','FAILED','SUCCEEDED'))",
                (r,n)->store.read(r.getString(1)),s.id());
        for(var prior:paidSources)if(prior.path("executionPlanId").equals(c.path("executionPlanId"))
                &&prior.path("currentStepId").equals(c.path("currentStepId"))&&prior.path("sourceRef").equals(f.path("target").path("sourceRef")))return null;
        for(var scene:step.path("scenes"))if(scene.path("sourceRef").equals(f.path("target").path("sourceRef"))&&"SUCCEEDED".equals(scene.path("status").asText()))return null;
        return f;
    }
    public JsonNode bindings(AgentStore store,Session s,JsonNode w) {
        var result=json.createArrayNode();if(!w.path("hasLocalRepairs").asBoolean())return result;
        db.query("SELECT id,binding_json FROM agent_local_repair WHERE session_id=? AND plan_id=? ORDER BY workspace_version",
                r->{var binding=(ObjectNode)store.read(r.getString(2));binding.put("bindingId",r.getString(1));result.add(binding);},s.id(),w.path("executionPlanId").asText());
        for(var binding:result)if("STORYBOARD".equals(binding.path("target").path("kind").asText())&&binding.hasNonNull("restartCallId")) {
            var produced=db.query("SELECT a.artifact_id,a.version_no,a.source_call_id,c.context_json,p.step_key,p.result_ref FROM agent_artifact_version a JOIN agent_plan_step p ON p.id=? AND p.plan_id=? AND p.status='SUCCEEDED' JOIN agent_skill_call c ON c.id=a.source_call_id AND c.skill_id='storyboard-generation' JOIN agent_turn t ON t.id=c.turn_id AND t.session_id=a.session_id WHERE a.session_id=? AND a.user_id=? AND a.type='STORYBOARD' AND p.skill_call_id=a.source_call_id",
                    (r,n)->{
                        var ref=json.createObjectNode().put("artifactId",r.getString(1)).put("version",r.getInt(2));var call=store.read(r.getString(4));
                        boolean authorized=binding.path("restartCallId").asText().equals(r.getString(3))||binding.path("bindingId").equals(call.path("confirmedStoryboardRepairId"));
                        return authorized&&call.path("executionPlanId").equals(w.path("executionPlanId"))&&call.path("currentStepId").asText().equals(r.getString(5))
                                &&call.path("sourceRef").equals(binding.path("target").path("sourceRef"))&&ref.equals(store.read(r.getString(6)))?ref:null;
                    },binding.path("target").path("executionStepId").asText(),w.path("executionPlanId").asText(),s.id(),s.userId()).stream().filter(Objects::nonNull).toList();
            if(produced.size()==1)((ObjectNode)binding).set("resultRef",produced.get(0));
        }
        return result;
    }
    public void confirm(AgentStore store,Session s,JsonNode failure,JsonNode params,String key,String hash) {
        var w=store.workspace(s);var current=current(store,s);
        if(current==null||!current.equals(failure))throw BusinessException.conflict("修复建议已失效，请刷新后重新选择");
        var binding=json.createObjectNode();binding.set("target",failure.path("target"));binding.set("spec",params);
        binding.set("baseline",failure.path("input"));
        db.update("INSERT INTO agent_local_repair(id,session_id,plan_id,workspace_version,failure_id,request_key,request_hash,binding_json) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),s.id(),w.path("executionPlanId").asText(),w.path("version").asLong()+1,
                failure.path("failureId").asText(),key,hash,store.write(binding));
        w.put("version",w.path("version").asLong()+1).put("hasLocalRepairs",true);store.saveWorkspace(s,w);
    }
    public String replay(Session s,String key) {
        return db.query("SELECT request_hash FROM agent_local_repair WHERE session_id=? AND request_key=?",(r,n)->r.getString(1),s.id(),key).stream().findFirst().orElse(null);
    }
    public String restart(AgentStore store,Session s,Turn next,Call old) {
        var w=store.workspace(s);
        db.update("UPDATE agent_plan_step SET status='RUNNING',skill_id=? WHERE plan_id=? AND step_key=? AND status NOT IN ('SUCCEEDED','SKIPPED')",
                old.skillId(),w.path("executionPlanId").asText(),w.path("currentStepId").asText());
        String call=store.newCall(next,old.skillId(),old.skillVersion(),store.read(old.input()));
        var rows=db.query("SELECT id,binding_json FROM agent_local_repair WHERE session_id=? AND plan_id=? AND workspace_version=?",(r,n)->new String[]{r.getString(1),r.getString(2)},s.id(),w.path("executionPlanId").asText(),w.path("version").asLong());
        if(rows.size()!=1)throw BusinessException.conflict("修复绑定已变化");
        var binding=(ObjectNode)store.read(rows.get(0)[1]);binding.put("restartCallId",call);
        db.update("UPDATE agent_local_repair SET binding_json=? WHERE id=?",store.write(binding),rows.get(0)[0]);
        store.executionStatus(next.id(),"WAITING_SKILL",null);return call;
    }
}
