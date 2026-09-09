package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.model.InvalidAgentDecisionException;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Per-scene facts under one sequential Plan step. Caller owns session lock/transaction. */
@RequiredArgsConstructor
class AgentSceneStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    void initialize(Session s,String step,String kind,JsonNode source) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE plan_step_id=?",Long.class,step)>0)return;
        var inflight=jdbc.query("SELECT c.context_json,p.quote_json FROM agent_approval p JOIN agent_skill_call c ON c.id=p.call_id WHERE p.session_id=? AND p.status IN ('PENDING','BATCH_PENDING','BATCH_QUEUED','APPROVED','SUBMITTING','ACCEPTED')",
                (r,n)->new String[]{r.getString(1),r.getString(2)},s.id());
        for(var pending:inflight) {
            var prior=read(pending[0]).path("sourceRef");
            if(source.path("artifactId").equals(prior.path("artifactId"))&&source.path("version").equals(prior.path("version"))
                    &&kind.equals(read(pending[1]).path("mediaType").asText()))
                throw BusinessException.conflict("本版分镜仍有已提交或待确认的生成任务，请等待原任务完成后再启动逐幕生成");
        }
        var rows=jdbc.query("SELECT data_json FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND version_no=? AND type='STORYBOARD'",
                (r,n)->r.getString(1),s.id(),s.userId(),source.path("artifactId").asText(),source.path("version").asInt());
        if(rows.isEmpty())throw BusinessException.badRequest("逐幕生成需要本对话的准确分镜版本");
        JsonNode data=read(rows.get(0)); validateBoard(data);
        int ordinal=0;
        for(var scene:data.path("scenes")) {
            String key=scene.path("sceneId").asText();
            var ref=json.createObjectNode().put("artifactId",source.path("artifactId").asText()).put("version",source.path("version").asInt()).put("sceneId",key);
            JsonNode result=null;
            // A persisted successful approval plus its exact task artifact is the accepted completion fact.
            var artifacts=jdbc.query("SELECT a.artifact_id,a.version_no,a.source_ref_json FROM agent_artifact_version a JOIN agent_approval p ON p.call_id=a.source_call_id AND p.task_id=a.task_id "
                    +"WHERE a.session_id=? AND a.user_id=? AND a.type=? AND p.status='SUCCEEDED' ORDER BY a.version_no DESC",
                    (r,n)->new Object[]{r.getString(1),r.getInt(2),r.getString(3)},s.id(),s.userId(),kind);
            for(var a:artifacts)if(ref.equals(read((String)a[2]))) {result=json.createObjectNode().put("artifactId",(String)a[0]).put("version",(Integer)a[1]);break;}
            jdbc.update("INSERT INTO agent_plan_scene(id,plan_step_id,scene_key,ordinal_no,status,source_ref,result_ref) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),step,key,++ordinal,result==null?"PENDING":"SUCCEEDED",ref.toString(),result==null?null:result.toString());
        }
        ready(step);
        completeParent(step);
    }
    static void validateBoard(JsonNode data) {
        JsonNode scenes=data==null?null:data.get("scenes");
        if(scenes==null||!scenes.isArray()||scenes.isEmpty()||scenes.size()>12)throw BusinessException.badRequest("分镜必须包含1至12幕");
        Set<String> ids=new HashSet<>();
        for(var scene:scenes)if(!scene.isObject()||!scene.path("sceneId").isTextual()||scene.path("sceneId").asText().isBlank()
                ||scene.path("sceneId").asText().length()>64||!ids.add(scene.path("sceneId").asText().toLowerCase(Locale.ROOT)))
            throw BusinessException.badRequest("分镜编号必须有效且唯一");
    }
    void project(String step,ObjectNode target) {
        var items=target.putArray("scenes");
        jdbc.query("SELECT * FROM agent_plan_scene WHERE plan_step_id=? ORDER BY ordinal_no",r->{
            var item=items.addObject().put("id",r.getString("id")).put("sceneId",r.getString("scene_key"))
                    .put("ordinal",r.getInt("ordinal_no")).put("status",r.getString("status"));
            item.set("sourceRef",read(r.getString("source_ref")));
            if(r.getString("result_ref")!=null)item.set("artifactRef",read(r.getString("result_ref")));
        },step);
        int done=0;Integer current=null;
        for(var item:items)if("SUCCEEDED".equals(item.path("status").asText()))done++;else if(current==null)current=item.path("ordinal").asInt();
        var progress=target.putObject("sceneProgress").put("completedScenes",done).put("totalScenes",items.size());
        if(current==null)progress.putNull("currentSceneNo");else progress.put("currentSceneNo",current);
    }
    ObjectNode current(String step) {
        var rows=jdbc.query("SELECT id,source_ref,status FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED' ORDER BY ordinal_no LIMIT 1",
                (r,n)->{var result=json.createObjectNode().put("id",r.getString(1)).put("status",r.getString(3));result.set("sourceRef",read(r.getString(2)));return result;},step);
        return rows.isEmpty()?null:rows.get(0);
    }
    void validate(String step,JsonNode source) {
        var item=current(step);
        if(item==null||!item.path("sourceRef").equals(source))throw new InvalidAgentDecisionException("逐幕步骤必须引用当前待生成幕的准确分镜版本及sceneId；不能跳幕或重做成功幕。");
        if(Set.of("FAILED","CANCELLED").contains(item.path("status").asText()))throw BusinessException.conflict("本幕任务未成功，已保留进度；请明确重新启动未完成幕，不能自动重投。");
    }
    void attach(String step,String call) {
        var item=current(step);if(item==null)return;
        jdbc.update("UPDATE agent_plan_scene SET skill_call_id=?,status='RUNNING',updated_at=NOW() WHERE id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",call,item.path("id").asText());
    }
    void state(String step,String status) {
        var item=current(step);if(item==null)return;
        // A turn cancellation/resume cannot cancel or reset a paid submission that still needs reconciliation.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_plan_scene s JOIN agent_approval a ON a.call_id=s.skill_call_id WHERE s.id=? AND a.status IN ('SUBMITTING','ACCEPTED')",Integer.class,item.path("id").asText())>0)return;
        // Failure remains visible through turn suspension and human resume; never silently reset a paid failure.
        if(Set.of("FAILED","CANCELLED").contains(item.path("status").asText()))return;
        jdbc.update("UPDATE agent_plan_scene SET status=?,updated_at=NOW() WHERE id=?",status,item.path("id").asText());
    }
    boolean succeeded(JsonNode context,AgentViews.Artifact artifact) {
        String id=context.path("sceneItemId").asText(null);if(id==null)return false;
        var rows=jdbc.query("SELECT s.plan_step_id FROM agent_plan_scene s JOIN agent_plan_step p ON p.id=s.plan_step_id "
                +"WHERE s.id=? AND p.plan_id=? AND p.step_key=?",(r,n)->r.getString(1),id,context.path("executionPlanId").asText(),context.path("currentStepId").asText());
        if(rows.isEmpty())throw new IllegalStateException("Scene completion identity missing");
        jdbc.update("UPDATE agent_plan_scene SET status='SUCCEEDED',result_ref=?,updated_at=NOW() WHERE id=? AND status<>'SUCCEEDED'",
                json.createObjectNode().put("artifactId",artifact.id()).put("version",artifact.version()).toString(),id);
        ready(rows.get(0));completeParent(rows.get(0));return true;
    }
    private void ready(String step) {
        var item=current(step);if(item!=null)jdbc.update("UPDATE agent_plan_scene SET status='READY' WHERE id=? AND status='PENDING'",item.path("id").asText());
    }
    private void completeParent(String step) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED'",Long.class,step)==0)
            jdbc.update("UPDATE agent_plan_step SET status='SUCCEEDED',error_code=NULL,updated_at=NOW() WHERE id=?",step);
        else jdbc.update("UPDATE agent_plan_step SET status='READY',error_code=NULL WHERE id=?",step);
    }
    private JsonNode read(String value) {try{return value==null?json.nullNode():json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
