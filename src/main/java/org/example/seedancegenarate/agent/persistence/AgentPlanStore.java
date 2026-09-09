package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.model.InvalidAgentDecisionException;
import org.example.seedancegenarate.agent.skill.SkillDescriptor;
import org.example.seedancegenarate.config.AgentRuntimeProperties;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Sequential dependency graph, serialized by the caller's session lock. Tables own execution state. */
public class AgentPlanStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentRuntimeProperties limits;
    private final AgentSceneStore scenes;
    private final AgentSceneEditStore edits;
    public AgentPlanStore(JdbcTemplate jdbc,ObjectMapper json,AgentRuntimeProperties limits) {
        this.jdbc=jdbc;this.json=json;this.limits=limits;this.scenes=new AgentSceneStore(jdbc,json);this.edits=new AgentSceneEditStore(jdbc,json,limits);
    }
    public AgentSceneEditStore edits(){return edits;}

    public void adopt(Session s,AgentViews.Artifact artifact,ObjectNode workspace) {
        if(find(s,workspace)!=null) return;
        org.example.seedancegenarate.agent.skill.CreationSpecSupport.validate(artifact.data()==null?null:artifact.data().get("creationSpec"));
        var steps=artifact.data()==null?null:artifact.data().get("steps");
        if(steps==null || !steps.isArray() || steps.isEmpty() || steps.size()>limits.getMaxPlanSteps())
            throw BusinessException.badRequest("计划必须包含1至"+limits.getMaxPlanSteps()+"个步骤");
        Set<String> ids=new HashSet<>();
        Set<String> storyboardIds=new HashSet<>();
        for(var step:steps) {
            if(!step.isObject() || !step.path("id").isTextual() || !step.path("id").asText().matches("[A-Za-z0-9_-]{1,32}")
                    || !ids.add(step.path("id").asText().toLowerCase(Locale.ROOT)) || !Set.of("SCRIPT","PROMPT","STORYBOARD","IMAGE","VIDEO","WEB_RESEARCH").contains(step.path("kind").asText())
                    || !step.path("title").isTextual() || step.path("title").asText().isBlank() || step.path("title").asText().length()>128
                    || step.has("dependsOn") || step.has("depends_on"))
                throw BusinessException.badRequest("计划步骤格式无效，本阶段仅支持按顺序执行的计划");
            org.example.seedancegenarate.agent.skill.StructuredSkillSupport.validatePlanStepScope(step,storyboardIds);
            if("STORYBOARD".equals(step.path("kind").asText()))storyboardIds.add(step.path("id").asText());
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_plan(id,session_id,artifact_id,artifact_version,status) VALUES(?,?,?,?,'READY')",id,s.id(),artifact.id(),artifact.version());
        String previous=null; int ordinal=0; boolean completePrefix=true;
        for(var step:steps) {
            JsonNode result=null;
            for(var old:workspace.path("steps")) if(step.path("id").equals(old.path("id")) && step.path("kind").equals(old.path("kind"))
                    && Set.of("COMPLETED","SUCCEEDED").contains(old.path("status").asText()) && old.hasNonNull("artifactRef")) result=old.get("artifactRef");
            if(step.has("scope") || !completePrefix || !validResult(s,step.path("kind").asText(),result)
                    || !sameCreationSpec(s,result,artifact.data().get("creationSpec"))) result=null;
            completePrefix=result!=null;
            String status=result!=null?"SUCCEEDED":ordinal==0?"READY":"PENDING";
            jdbc.update("INSERT INTO agent_plan_step(id,plan_id,step_key,ordinal_no,kind,title,depends_on,status,result_ref) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),id,step.path("id").asText(),ordinal++,step.path("kind").asText(),step.path("title").asText(),
                    write(previous==null?List.of():List.of(previous)),status,result==null?null:write(result));
            previous=step.path("id").asText();
            if(step.has("scope"))jdbc.update("UPDATE agent_plan_step SET scope='STORYBOARD_SCENES',source_step_key=? WHERE plan_id=? AND step_key=?",step.path("sourceStepId").asText(),id,previous);
        }
        ready(id);
    }
    /** Explicit command creates one real adopted Plan; no synthetic LLM SkillCall or billing path. */
    public AgentViews.Artifact startScenes(AgentStore store,Session s,AgentViews.Artifact board,String kind,ObjectNode w) {
        if(!"STORYBOARD".equals(board.type()))throw BusinessException.badRequest("请选择完整分镜作品");
        AgentSceneStore.validateBoard(board.data());
        var artifact=store.artifact(s,"scene-plan:"+UUID.randomUUID(),null,"PLAN","逐幕生成"+("IMAGE".equals(kind)?"参考图":"视频"),"沿用本版已有结果，逐幕确认生成费用，不自动合成。");
        var data=json.createObjectNode().put("goal","为分镜《"+board.title()+"》的所有幕生成"+("IMAGE".equals(kind)?"参考图":"视频")+"，已有成功作品保留。");
        var creationSpec=org.example.seedancegenarate.agent.skill.CreationSpecSupport.merge(null,board.data().get("creationSpec"));
        if(creationSpec!=null)data.set("creationSpec",creationSpec);
        data.putArray("constraints").add("每一幕单独确认费用；不重复生成已有成功作品");
        data.putArray("steps").addObject().put("id","scenes").put("kind",kind).put("title","逐幕生成");
        jdbc.update("UPDATE agent_artifact_version SET data_json=? WHERE session_id=? AND artifact_id=? AND version_no=1",data.toString(),s.id(),artifact.id());
        artifact=store.artifactVersion(s,artifact.id(),1);
        w.set("planRef",json.createObjectNode().put("artifactId",artifact.id()).put("version",1));w.put("planConfirmed",true);w.putArray("steps");w.putNull("selection");
        adopt(s,artifact,w);
        String plan=find(s,w);String step=jdbc.queryForObject("SELECT id FROM agent_plan_step WHERE plan_id=?",String.class,plan);
        var ref=json.createObjectNode().put("artifactId",board.id()).put("version",board.version());
        jdbc.update("UPDATE agent_plan_step SET scope='STORYBOARD_SCENES',source_ref=? WHERE id=?",ref.toString(),step);
        ((ObjectNode)data.path("steps").get(0)).put("scope","STORYBOARD_SCENES").set("sourceRef",ref);
        jdbc.update("UPDATE agent_artifact_version SET data_json=? WHERE session_id=? AND artifact_id=? AND version_no=1",data.toString(),s.id(),artifact.id());
        scenes.initialize(s,step,kind,ref);return store.artifactVersion(s,artifact.id(),1);
    }
    /** Called only inside Runtime/command transactions, not from snapshots. */
    public ObjectNode currentScene(Session s,Turn t) {
        String plan=bound(t);if(plan==null)return null;
        for(int skipped=0;skipped<=limits.getMaxPlanSteps();skipped++) {
        var rows=jdbc.query("SELECT id,kind,source_step_key,source_ref,scope FROM agent_plan_step WHERE plan_id=? AND status NOT IN ('SUCCEEDED','SKIPPED') ORDER BY ordinal_no LIMIT 1",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)},plan);
        if(rows.isEmpty()||!"STORYBOARD_SCENES".equals(rows.get(0)[4]))return null;
        var row=rows.get(0);JsonNode source=row[3]==null?null:read(row[3]);
        if(source==null) {
            var refs=jdbc.query("SELECT result_ref FROM agent_plan_step WHERE plan_id=? AND step_key=? AND kind='STORYBOARD' AND status='SUCCEEDED'",(r,n)->r.getString(1),plan,row[2]);
            if(refs.isEmpty()||refs.get(0)==null)throw BusinessException.badRequest("当前逐幕步骤缺少已完成分镜");
            source=read(refs.get(0));jdbc.update("UPDATE agent_plan_step SET source_ref=? WHERE id=?",write(source),row[0]);
        }
        scenes.initialize(s,row[0],row[1],source);ready(plan);
        var current=scenes.current(row[0]);if(current!=null)return current;
        }
        throw new IllegalStateException("Scene initialization exceeded plan bound");
    }
    /** Reuse only proven matching output metadata; unknown legacy targets never masquerade as new confirmed values. */
    private boolean sameCreationSpec(Session s,JsonNode ref,JsonNode target) {
        var data=jdbc.queryForObject("SELECT data_json FROM agent_artifact_version WHERE session_id=? AND artifact_id=? AND version_no=?",
                String.class,s.id(),ref.path("artifactId").asText(),ref.path("version").asInt());
        var source=data==null?null:read(data).get("creationSpec");
        return Objects.equals(org.example.seedancegenarate.agent.skill.CreationSpecSupport.merge(null,target),
                org.example.seedancegenarate.agent.skill.CreationSpecSupport.merge(null,source));
    }
    private boolean validResult(Session s,String kind,JsonNode ref) {
        return ref!=null && ref.isObject() && ref.path("artifactId").isTextual() && ref.path("version").isIntegralNumber()
                && ref.path("version").canConvertToInt() && ref.path("version").asInt()>0
                && jdbc.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND version_no=? AND type=?",
                Long.class,s.id(),s.userId(),ref.path("artifactId").asText(),ref.path("version").asInt(),kind)>0;
    }
    private String find(Session s,JsonNode w) {
        if(!w.path("planConfirmed").asBoolean() || !w.hasNonNull("planRef")) return null;
        var ids=jdbc.query("SELECT id FROM agent_plan WHERE session_id=? AND artifact_id=? AND artifact_version=?",
                (r,n)->r.getString(1),s.id(),w.path("planRef").path("artifactId").asText(),w.path("planRef").path("version").asInt());
        return ids.isEmpty()?null:ids.get(0);
    }
    public ObjectNode project(Session s,ObjectNode w) {
        w.remove(List.of("executionPlanId","executionStatus","executionReason","planProgress","sceneEdit"));
        String id=find(s,w); if(id==null) return w;
        jdbc.query("SELECT status,reason FROM agent_plan WHERE id=?",r->{w.put("executionPlanId",id).put("executionStatus",r.getString(1)).put("executionReason",r.getString(2));},id);
        var steps=w.putArray("steps");
        jdbc.query("SELECT * FROM agent_plan_step WHERE plan_id=? ORDER BY ordinal_no",r->{
            var step=steps.addObject().put("id",r.getString("step_key")).put("executionStepId",r.getString("id"))
                    .put("kind",r.getString("kind")).put("title",r.getString("title")).put("status",r.getString("status"))
                    .put("skillId",r.getString("skill_id")).put("errorCode",r.getString("error_code"));
            step.put("error",r.getString("error_code")==null?null:w.path("executionReason").asText("当前步骤未完成，请检查运行状态后继续。"));
            step.set("dependsOn",read(r.getString("depends_on")));
            if(r.getString("result_ref")!=null) step.set("artifactRef",read(r.getString("result_ref")));
            if("STORYBOARD_SCENES".equals(r.getString("scope"))) {
                step.put("scope","STORYBOARD_SCENES").put("sourceStepId",r.getString("source_step_key"));scenes.project(r.getString("id"),step);
            }
        },id);
        w.putNull("currentStepId");
        for(var step:steps) if(!Set.of("SUCCEEDED","SKIPPED").contains(step.path("status").asText())) { w.put("currentStepId",step.path("id").asText()); break; }
        jdbc.query("SELECT COUNT(*) total_steps,"
                +"SUM(CASE WHEN status IN ('SUCCEEDED','SKIPPED') THEN 1 ELSE 0 END) completed_steps,"
                +"MIN(CASE WHEN status NOT IN ('SUCCEEDED','SKIPPED') THEN ordinal_no+1 ELSE NULL END) current_step_no "
                +"FROM agent_plan_step WHERE plan_id=?",r->{
            var progress=w.putObject("planProgress").put("completedSteps",r.getInt("completed_steps")).put("totalSteps",r.getInt("total_steps"));
            Number current=(Number)r.getObject("current_step_no");
            if(current==null) progress.putNull("currentStepNo"); else progress.put("currentStepNo",current.intValue());
        },id);
        edits.project(s,w);return w;
    }
    void rebindForSystemContinue(Turn oldTurn,Turn child) {
        jdbc.update("UPDATE agent_plan SET turn_id=?,execution_epoch=?,status='RUNNING',reason=NULL,updated_at=NOW() "
                +"WHERE turn_id=? AND execution_epoch=? AND status<>'CANCELLED'",
                child.id(),child.epoch(),oldTurn.id(),oldTurn.epoch());
    }
    public void bind(Session s,Turn t,ObjectNode workspace) {
        String id=find(s,workspace); if(id==null) return;
        if("SUCCEEDED".equals(jdbc.queryForObject("SELECT status FROM agent_plan WHERE id=?",String.class,id)))return;
        jdbc.update("UPDATE agent_plan SET turn_id=?,execution_epoch=?,status='RUNNING',reason=NULL,updated_at=NOW() WHERE id=?",t.id(),t.epoch(),id);
        jdbc.update("UPDATE agent_plan_step SET status='PENDING',error_code=NULL WHERE plan_id=? AND status NOT IN ('SUCCEEDED','SKIPPED')",id);
        ready(id);
    }
    private String bound(Turn t) {
        if(t==null) return null;
        var ids=jdbc.query("SELECT id FROM agent_plan WHERE turn_id=? AND execution_epoch=? AND status<>'CANCELLED'",(r,n)->r.getString(1),t.id(),t.epoch());
        return ids.isEmpty()?null:ids.get(0);
    }
    private void ready(String plan) {
        var ids=jdbc.query("SELECT id FROM agent_plan_step WHERE plan_id=? AND status NOT IN ('SUCCEEDED','SKIPPED') ORDER BY ordinal_no LIMIT 1",(r,n)->r.getString(1),plan);
        if(!ids.isEmpty()) jdbc.update("UPDATE agent_plan_step SET status='READY' WHERE id=? AND status='PENDING'",ids.get(0));
    }
    public void state(Turn t,String status,String reason) {
        String plan=bound(t); if(plan==null) return;
        String planStatus=switch(status) {
            case "QUEUED","RUNNING","WAITING_SKILL" -> "RUNNING";
            case "FAILED","SUSPENDED" -> "SUSPENDED";
            case "WAITING_USER","WAITING_APPROVAL","WAITING_TASK","WAITING_RETRY","CANCELLED" -> status;
            default -> null;
        };
        if(planStatus==null)return; // Turn completion/yield is not a Plan business outcome.
        jdbc.update("UPDATE agent_plan SET status=?,reason=?,updated_at=NOW() WHERE id=? AND status NOT IN ('SUCCEEDED','CANCELLED')",planStatus,reason,plan);
        String stepStatus=switch(status) {
            case "WAITING_USER","WAITING_APPROVAL","WAITING_TASK","WAITING_RETRY","CANCELLED" -> status;
            case "RUNNING","WAITING_SKILL" -> "RUNNING";
            case "QUEUED" -> "READY";
            case "SUSPENDED","FAILED" -> "SUSPENDED";
            default -> null;
        };
        if(stepStatus!=null) {
            var rows=jdbc.query("SELECT id FROM agent_plan_step WHERE plan_id=? AND status NOT IN ('SUCCEEDED','SKIPPED') ORDER BY ordinal_no LIMIT 1",(r,n)->r.getString(1),plan);
            if(!rows.isEmpty()) {
                jdbc.update("UPDATE agent_plan_step SET status=?,error_code=?,updated_at=NOW() WHERE id=?",stepStatus,reason==null?null:status,rows.get(0));
                scenes.state(rows.get(0),stepStatus);
            }
        }
        if("CANCELLED".equals(status)) jdbc.update("UPDATE agent_plan_step SET status='CANCELLED' WHERE plan_id=? AND status NOT IN ('SUCCEEDED','SKIPPED')",plan);
    }
    record Completion(boolean valid,String summary,boolean video) {}
    /** Null means still in progress. Validate actual provenance, not just successful status flags. */
    Completion completion(Session s,Turn t,JsonNode workspace) {
        String plan=bound(t);
        if(plan==null||!plan.equals(workspace.path("executionPlanId").asText())||!workspace.path("planConfirmed").asBoolean())return null;
        String status=jdbc.queryForObject("SELECT status FROM agent_plan WHERE id=?",String.class,plan);
        if(Set.of("SUCCEEDED","CANCELLED","SUSPENDED","FAILED").contains(status))return null;
        var steps=jdbc.query("SELECT id,kind,status,result_ref,skill_call_id,scope,source_ref FROM agent_plan_step WHERE plan_id=? ORDER BY ordinal_no",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)},plan);
        if(steps.stream().anyMatch(row->!Set.of("SUCCEEDED","SKIPPED").contains(row[2])))return null;
        boolean valid=!steps.isEmpty();var counts=new LinkedHashMap<String,Integer>();
        for(var step:steps) {
            if("SKIPPED".equals(step[2]))continue;
            if("STORYBOARD_SCENES".equals(step[5])) {
                var source=readReference(step[6]);
                var boards=jdbc.query("SELECT data_json FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND version_no=? AND type='STORYBOARD'",
                        (r,n)->readReference(r.getString(1)),s.id(),s.userId(),source.path("artifactId").asText(),source.path("version").asInt());
                var scenes=jdbc.query("SELECT scene_key,status,source_ref,result_ref,skill_call_id FROM agent_plan_scene WHERE plan_step_id=? ORDER BY ordinal_no",
                        (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)},step[0]);
                Set<String> keys=new HashSet<>();if(!boards.isEmpty())for(var scene:boards.get(0).path("scenes"))keys.add(scene.path("sceneId").asText());
                valid&=!scenes.isEmpty()&&keys.size()==scenes.size();
                for(var scene:scenes) {
                    var expected=source.deepCopy();if(expected.isObject())((ObjectNode)expected).put("sceneId",scene[0]);
                    var retained=readReference(scene[2]);
                    valid&=keys.remove(scene[0])&&"SUCCEEDED".equals(scene[1])&&!boards.isEmpty()
                            &&sameSceneSource(s,expected,retained,boards.get(0))
                            &&delivered(s,step[1],readReference(scene[3]),scene[4],retained);
                    counts.merge(step[1],1,Integer::sum);
                }
                valid&=keys.isEmpty();
            } else {
                valid&=delivered(s,step[1],readReference(step[3]),step[4],null);
                counts.merge(step[1],1,Integer::sum);
            }
        }
        StringJoiner summary=new StringJoiner("、");
        var labels=Map.of("SCRIPT","脚本","STORYBOARD","分镜","PROMPT","提示词","IMAGE","图片","VIDEO","视频片段","WEB_RESEARCH","资料");
        counts.forEach((kind,count)->summary.add(labels.getOrDefault(kind,kind)+" "+count+" 份"));
        return new Completion(valid,summary.length()==0?"所有步骤已按计划跳过":summary.toString(),counts.containsKey("VIDEO"));
    }
    private boolean delivered(Session s,String kind,JsonNode ref,String expectedCall,JsonNode source) {
        if(!validResult(s,kind,ref))return false;
        var rows=jdbc.query("SELECT a.source_call_id,a.source_ref_json,a.task_id,c.status FROM agent_artifact_version a LEFT JOIN agent_skill_call c ON c.id=a.source_call_id WHERE a.session_id=? AND a.artifact_id=? AND a.version_no=?",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},s.id(),ref.path("artifactId").asText(),ref.path("version").asInt());
        if(rows.size()!=1)return false;var result=rows.get(0);
        if(expectedCall!=null&&!expectedCall.equals(result[0])||!"SUCCEEDED".equals(result[3]))return false;
        if(source!=null&&!source.equals(readReference(result[1])))return false;
        return !Set.of("IMAGE","VIDEO").contains(kind)||jdbc.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE call_id=? AND task_id=? AND status='SUCCEEDED'",Long.class,result[0],result[2])==1;
    }
    boolean complete(Turn t) {
        String plan=bound(t);return plan!=null&&jdbc.update("UPDATE agent_plan SET status='SUCCEEDED',reason=NULL,updated_at=NOW() WHERE id=? AND status NOT IN ('SUCCEEDED','CANCELLED','SUSPENDED','FAILED')",plan)==1;
    }
    private JsonNode readReference(String raw) {return raw==null?json.nullNode():read(raw);}
    /** A local scene edit deliberately keeps successful, byte-equivalent unmodified scenes on their old version. */
    private boolean sameSceneSource(Session s,JsonNode current,JsonNode retained,JsonNode currentBoard) {
        if(current.equals(retained))return true;
        if(!current.path("artifactId").equals(retained.path("artifactId"))||!current.path("sceneId").equals(retained.path("sceneId"))
                ||!retained.path("version").isIntegralNumber()||retained.path("version").asInt()<1||retained.path("version").asInt()>=current.path("version").asInt())return false;
        var old=jdbc.query("SELECT data_json FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND version_no=? AND type='STORYBOARD'",
                (r,n)->readReference(r.getString(1)),s.id(),s.userId(),retained.path("artifactId").asText(),retained.path("version").asInt());
        if(old.size()!=1)return false;
        for(var scene:currentBoard.path("scenes"))if(scene.path("sceneId").equals(current.path("sceneId")))
            for(var prior:old.get(0).path("scenes"))if(scene.equals(prior))return true;
        return false;
    }
    public void validateCall(Session s,Turn t,SkillDescriptor skill,JsonNode input,ObjectNode w) {
        validateCall(s,t,skill,input,w,false);
    }
    public void validateCall(Session s,Turn t,SkillDescriptor skill,JsonNode input,ObjectNode w,boolean recipeDerivation) {
        String plan=bound(t); if(plan==null) return;
        JsonNode target=null;
        for(var step:w.path("steps")) if(step.path("id").asText().equals(w.path("currentStepId").asText())) target=step;
        // A source reference is not permission to redo a successful step. Stop the plan before revising it.
        var source="web-search".equals(skill.id())?null:input.hasNonNull("source")?input.get("source"):w.get("selection");
        if(!edits.permits(w,source) && (input.hasNonNull("artifactId") || !recipeDerivation && source!=null && !source.isNull() && jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_artifact_version WHERE session_id=? AND artifact_id=? AND version_no=? AND type=?",
                Long.class,s.id(),source.path("artifactId").asText(),source.path("version").asInt(),skill.resultType())>0))
            throw new InvalidAgentDecisionException("自动计划步骤只能创建新作品；同类型source/artifactId意味着改稿，请先停止计划后修改。下游可引用不同类型作品作为输入。");
        if(target==null || !Objects.equals(skill.resultType(),target.path("kind").asText()))
            throw new InvalidAgentDecisionException("skillId/resultType 必须匹配当前未完成步骤；不能重复执行已成功步骤。改稿须先停止计划，再按精确作品版本修改。");
        if("STORYBOARD_SCENES".equals(target.path("scope").asText()))scenes.validate(target.path("executionStepId").asText(),source);
        jdbc.update("UPDATE agent_plan_step SET status='RUNNING',skill_id=?,input_json=?,error_code=NULL WHERE plan_id=? AND step_key=?",
                skill.id(),write(input),plan,target.path("id").asText());
    }
    public void attach(Turn t,String call,String skill) {
        String plan=bound(t); if(plan==null) return;
        jdbc.update("UPDATE agent_plan_step SET skill_call_id=? WHERE plan_id=? AND skill_id=? AND status='RUNNING'",call,plan,skill);
        jdbc.query("SELECT id FROM agent_plan_step WHERE plan_id=? AND skill_call_id=? AND scope='STORYBOARD_SCENES'",r->{scenes.attach(r.getString(1),call);},plan,call);
    }
    public void succeeded(JsonNode context,AgentViews.Artifact artifact) {
        String plan=context.path("executionPlanId").asText(null); if(plan==null) return;
        if(edits.finishStoryboard(context,artifact)){ready(plan);return;}
        if(scenes.succeeded(context,artifact)){ready(plan);return;}
        var ref=json.createObjectNode().put("artifactId",artifact.id()).put("version",artifact.version());
        var rows=jdbc.query("SELECT step_key,ordinal_no,result_ref FROM agent_plan_step WHERE plan_id=? AND kind=? ORDER BY ordinal_no",
                (r,n)->Map.of("key",r.getString(1),"ordinal",r.getInt(2),"ref",r.getString(3)==null?"null":r.getString(3)),plan,artifact.type());
        for(var row:rows) {
            boolean revision=artifact.version()>1 && artifact.id().equals(read((String)row.get("ref")).path("artifactId").asText());
            if(!revision && !row.get("key").equals(context.path("currentStepId").asText())) continue;
            jdbc.update("UPDATE agent_plan_step SET status='SUCCEEDED',result_ref=?,error_code=NULL,updated_at=NOW() WHERE plan_id=? AND step_key=?",write(ref),plan,row.get("key"));
            if("STORYBOARD".equals(artifact.type())&&!revision) {
                var owner=jdbc.query("SELECT s.* FROM agent_session s JOIN agent_plan p ON p.session_id=s.id WHERE p.id=?",(r,n)->new Session(r.getString("id"),r.getLong("conversation_id"),r.getLong("user_id"),r.getLong("revision"),r.getString("goal"),r.getString("summary"),r.getString("active_turn_id")),plan).get(0);
                jdbc.query("SELECT id,kind FROM agent_plan_step WHERE plan_id=? AND source_step_key=? AND scope='STORYBOARD_SCENES'",r->{
                    jdbc.update("UPDATE agent_plan_step SET source_ref=? WHERE id=?",write(ref),r.getString(1));
                    scenes.initialize(owner,r.getString(1),r.getString(2),ref);
                },plan,row.get("key"));
            }
            if(revision) jdbc.update("UPDATE agent_plan_step SET status='PENDING',result_ref=NULL,skill_call_id=NULL,error_code=NULL WHERE plan_id=? AND ordinal_no>?",plan,row.get("ordinal"));
            ready(plan); return;
        }
    }
    public void sceneFailed(JsonNode context,String status) {
        if(!context.hasNonNull("sceneItemId"))return;
        jdbc.update("UPDATE agent_plan_scene SET status=?,updated_at=NOW() WHERE id=? AND status<>'SUCCEEDED'",
                "CANCELLED".equals(status)?"CANCELLED":"FAILED",context.path("sceneItemId").asText());
    }
    public void observe(Turn t,Call call,String type,String code,String detail) {
        jdbc.update("INSERT INTO agent_observation(id,turn_id,execution_epoch,decision_seq,skill_call_id,type,code,detail) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),t.id(),t.epoch(),t.step(),call==null?null:call.id(),type,code,detail);
    }
    public void observeOutputRepair(Turn t,Call call,String detail) {
        jdbc.update("INSERT INTO agent_observation(id,turn_id,execution_epoch,decision_seq,skill_call_id,type,code,detail) VALUES(?,?,?,?,?,'SKILL_OUTPUT_REPAIR','SKILL_OUTPUT_INVALID',?) "
                        +"ON DUPLICATE KEY UPDATE detail=?",
                UUID.randomUUID().toString(),t.id(),t.epoch(),t.step(),call.id(),detail,detail);
    }
    /** Latest failure of each model phase; retries are not new decisions or decision-repair failures. */
    public void observeModelError(Turn t,Call call,String code) {
        String detail="模型调用未完成；没有产生新的作品或提交媒体任务。";
        jdbc.update("INSERT INTO agent_observation(id,turn_id,execution_epoch,decision_seq,skill_call_id,type,code,detail) VALUES(?,?,?,?,?,?,?,?) "
                        +"ON DUPLICATE KEY UPDATE code=?,detail=?",
                UUID.randomUUID().toString(),t.id(),t.epoch(),t.step(),call==null?null:call.id(),
                call==null?"MODEL_DECISION_ERROR":"MODEL_SKILL_ERROR",code,detail,code,detail);
    }
    /** Several scenes may repair within one Decision; this is not a new terminal ERROR observation. */
    public void observePreparationRepair(Turn t,Call call,int scene) {
        String detail="第 "+scene+" 幕视频提示词未通过校验，正在自动修正一次；尚未提交生成任务。";
        jdbc.update("INSERT INTO agent_observation(id,turn_id,execution_epoch,decision_seq,skill_call_id,type,code,detail) VALUES(?,?,?,?,?,?,?,?) "
                        +"ON DUPLICATE KEY UPDATE detail=?",
                UUID.randomUUID().toString(),t.id(),t.epoch(),t.step(),call.id(),"VIDEO_PROMPT_REPAIR","VIDEO_PROMPT_OUTPUT_INVALID",detail,detail);
    }
    public JsonNode observations(Turn t) {
        var result=json.createArrayNode();
        jdbc.query("SELECT type,code,detail,decision_seq,skill_call_id FROM agent_observation WHERE turn_id=? AND execution_epoch=? ORDER BY decision_seq DESC LIMIT 8",
                r->{result.addObject().put("type",r.getString(1)).put("code",r.getString(2)).put("detail",r.getString(3)).put("decisionSeq",r.getInt(4)).put("skillCallId",r.getString(5));},t.id(),t.epoch());
        return result;
    }
    public long failures(Turn t) {
        // Rejected decisions roll back; a committed earlier decision breaks the repair streak.
        // Human resume starts a fresh bounded streak without erasing observations or global budgets.
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_observation WHERE turn_id=? AND execution_epoch=? "
                +"AND type='ERROR' AND code='INVALID_DECISION' "
                +"AND decision_seq >= (SELECT budget_start FROM agent_turn WHERE id=?) "
                +"AND decision_seq > COALESCE((SELECT MAX(step_no) FROM agent_decision WHERE turn_id=? AND epoch=? AND step_no<?),-1)",
                Long.class,t.id(),t.epoch(),t.id(),t.id(),t.epoch(),t.step());
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private JsonNode read(String value) { try { return json.readTree(value); } catch(Exception e) { throw new IllegalStateException(e); } }
}
