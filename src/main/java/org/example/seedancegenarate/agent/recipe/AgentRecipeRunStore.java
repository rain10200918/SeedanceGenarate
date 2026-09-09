package org.example.seedancegenarate.agent.recipe;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.model.InvalidAgentDecisionException;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.skill.SkillDescriptor;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Run facts are serialized by the existing session lock; definitions are immutable published versions. */
@RequiredArgsConstructor
public class AgentRecipeRunStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record Run(String id,String sessionId,String versionId,String turnId,long epoch,long version,int stage,
            String status,ObjectNode variables,ObjectNode results,ObjectNode approvals,ObjectNode planSteps,
            int decisions,int interactions,String reason) {}
    public Run latest(Session s) {
        String id=jdbc.queryForObject("SELECT active_recipe_run_id FROM agent_session WHERE id=?",String.class,s.id());
        // Compare identifiers to parameters, not legacy/new-table columns with potentially different MySQL collations.
        return id==null?null:first(jdbc.query("SELECT * FROM agent_recipe_run WHERE session_id=? AND id=?",(r,n)->row(r),s.id(),id));
    }
    public Run bound(Turn t) {
        return t==null?null:first(jdbc.query("SELECT * FROM agent_recipe_run WHERE turn_id=? AND status<>'CANCELLED'",(r,n)->row(r),t.id()));
    }
    private Run row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Run(r.getString("id"),r.getString("session_id"),r.getString("recipe_version_id"),r.getString("turn_id"),r.getLong("execution_epoch"),r.getLong("version"),
                r.getInt("stage_index"),r.getString("status"),read(r.getString("variables_json")),read(r.getString("results_json")),read(r.getString("approvals_json")),read(r.getString("plan_steps_json")),
                r.getInt("decision_count"),r.getInt("interaction_count"),r.getString("reason"));
    }
    public RecipeDefinition definition(Run run) {
        String value=jdbc.queryForObject("SELECT definition_json FROM creative_recipe_version WHERE id=?",String.class,run.versionId());
        try {return json.readValue(value,RecipeDefinition.class);} catch(Exception e) {throw new IllegalStateException("Published recipe is invalid",e);}
    }
    public void create(Session s,String versionId,Turn t,String goal) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,turn_id,goal,variables_json,results_json,approvals_json) VALUES(?,?,?,?,?,'{}','{}','{}')",
                id,s.id(),versionId,t.id(),goal);
        jdbc.update("UPDATE agent_session SET active_recipe_run_id=? WHERE id=?",id,s.id());
    }
    public void bind(Session s,Turn t) {
        var run=latest(s); if(run==null||Set.of("CANCELLED","COMPLETED").contains(run.status())) return;
        jdbc.update("UPDATE agent_recipe_run SET turn_id=?,status='RUNNING',version=version+1,updated_at=NOW() WHERE id=?",t.id(),run.id());
    }
    public void state(Turn t,String status,String reason) {
        var run=bound(t); if(run==null||"COMPLETED".equals(run.status())) return;
        if(Set.of("FAILED","SUSPENDED","WAITING_RETRY").contains(status)) update(run,status,reason);
        else if("WAITING_RETRY".equals(run.status())&&Set.of("RUNNING","WAITING_SKILL").contains(status)) update(run,"RUNNING",null);
    }
    public void stop(Session s,boolean terminal) {
        var r=latest(s); if(r==null||Set.of("CANCELLED","COMPLETED").contains(r.status()))return;
        jdbc.update("UPDATE agent_recipe_run SET status=?,execution_epoch=execution_epoch+1,version=version+1,reason=?,updated_at=NOW() WHERE id=?",
                terminal?"CANCELLED":"SUSPENDED",terminal?"已停止技能运行，作品保留":"创作状态已改变，请明确恢复技能运行",r.id());
    }
    public JsonNode freeze(Turn t) {
        var r=bound(t); return r==null?null:json.createObjectNode().put("id",r.id()).put("epoch",r.epoch()).put("stage",r.stage());
    }
    public boolean matches(Turn t,JsonNode frozen) {
        var r=bound(t);
        return frozen==null||frozen.isNull()?r==null:r!=null&&r.id().equals(frozen.path("id").asText())&&r.epoch()==frozen.path("epoch").asLong()&&r.stage()==frozen.path("stage").asInt();
    }
    public List<RecipeDefinition.Variable> fields(Run r) {
        var d=definition(r); Set<String> names=new HashSet<>();
        d.variables().stream().filter(RecipeDefinition.Variable::required).forEach(v->names.add(v.id()));
        if(r.stage()<d.stages().size())names.addAll(d.stages().get(r.stage()).requiredVariables());
        return d.variables().stream().filter(v->names.contains(v.id())&&!r.variables().hasNonNull(v.id()))
                .map(v->new RecipeDefinition.Variable(v.id(),v.label(),true,v.options())).toList();
    }
    private ObjectNode requiredArtifact(Run r,String type,AgentStore store,Session s) {
        if(type==null)return null;
        var stages=definition(r).stages();
        for(int i=Math.min(r.stage(),stages.size())-1;i>=0;i--) {
            var result=r.results().path(stages.get(i).id());if(!type.equals(result.path("type").asText()))continue;
            var a=store.artifactVersion(s,result.path("artifactId").asText(),result.path("version").asInt());
            if(!store.latestArtifact(s,a.id(),a.version()))throw BusinessException.conflict("阶段来源作品已修改，请停止本次技能后重新开始");
            return json.createObjectNode().put("artifactId",a.id()).put("version",a.version()).put("title",a.title()).put("type",a.type());
        }
        return null;
    }
    /** Returns false only after a durable gate/terminal status has been written. No model call is made. */
    public boolean beforeStep(AgentStore store,Session s,Turn t,boolean countDecision) {
        var r=bound(t); if(r==null)return true;
        var d=definition(r);
        if(!fields(r).isEmpty())return waitFor(store,s,t,r,"WAITING_INPUT","请先填写当前阶段需要的信息");
        for(var input:d.requiredInputs()) {
            long count=0;for(var a:r.results())if(input.type().equals(a.path("type").asText()))count++;
            if(count<input.minCount())return waitFor(store,s,t,r,"SUSPENDED","该技能需要已验证的参考素材，本批尚不支持从技能入口提供该输入");
        }
        while(r.stage()<d.stages().size()) {
            var stage=d.stages().get(r.stage());
            if(!fields(r).isEmpty())return waitFor(store,s,t,r,"WAITING_INPUT","请补充「"+stage.title()+"」需要的信息");
            var artifact=requiredArtifact(r,stage.requiresArtifactType(),store,s);
            if(stage.requiresArtifactType()!=null&&artifact==null)return waitFor(store,s,t,r,"SUSPENDED","当前阶段缺少本次运行生成的前置作品，请停止后调整技能或计划");
            if(stage.requiresArtifactApproval()&&!Objects.equals(r.approvals().get(stage.id()),artifact))
                return waitFor(store,s,t,r,"WAITING_CONFIRMATION","请确认当前作品版本后继续「"+stage.title()+"」");
            if(stage.outputType()!=null)break;
            jdbc.update("UPDATE agent_recipe_run SET stage_index=stage_index+1,version=version+1,updated_at=NOW() WHERE id=?",r.id());r=bound(t);
        }
        if(r.stage()==d.stages().size()) {
            for(var rule:d.acceptanceRules()) if(requiredArtifact(r,rule.artifactType(),store,s)==null)
                return waitFor(store,s,t,r,"SUSPENDED","缺少技能验收要求的真实作品，请调整计划");
            if(!"COMPLETED".equals(r.status()))update(r,"COMPLETED",null);return true;
        }
        if(countDecision) {
            String key=t.id()+":"+t.epoch()+":"+t.step();
            String last=jdbc.queryForObject("SELECT last_decision_key FROM agent_recipe_run WHERE id=?",String.class,r.id());
            if(r.decisions()>=64&&!key.equals(last))return waitFor(store,s,t,r,"SUSPENDED","本次技能已达到64次决策预算，请停止并重新评估需求");
            jdbc.update("UPDATE agent_recipe_run SET decision_count=decision_count+1,last_decision_key=?,status='RUNNING',reason=NULL WHERE id=? AND (last_decision_key IS NULL OR last_decision_key<>?)",key,r.id(),key);
        }
        return true;
    }
    private boolean waitFor(AgentStore store,Session s,Turn t,Run r,String status,String reason) {
        store.executionStatus(t.id(),"SUSPENDED".equals(status)?"SUSPENDED":"WAITING_USER",reason);
        update(r,status,reason);
        store.message(s,t.id(),"ASSISTANT",reason,json.createArrayNode().add(json.createObjectNode().put("type","text").put("text",reason)),null,null);
        store.touch(s);return false;
    }
    private void update(Run r,String status,String reason) {
        jdbc.update("UPDATE agent_recipe_run SET status=?,reason=?,version=version+1,updated_at=NOW() WHERE id=?",status,reason,r.id());
    }
    /** True only for a validated text-stage dependency that must create a new work, even of the same type. */
    public boolean validateCall(AgentStore store,Session s,Turn t,SkillDescriptor skill,JsonNode input) {
        var r=bound(t);if(r==null)return false;
        var d=definition(r);
        if(r.stage()>=d.stages().size())throw new InvalidAgentDecisionException("技能运行已完成，不得继续创建作品");
        if(!fields(r).isEmpty())throw new InvalidAgentDecisionException("必须等待用户填写阶段变量");
        var stage=d.stages().get(r.stage());
        var artifact=requiredArtifact(r,stage.requiresArtifactType(),store,s);
        if(stage.requiresArtifactType()!=null&&artifact==null||stage.requiresArtifactApproval()&&!Objects.equals(r.approvals().get(stage.id()),artifact))
            throw new InvalidAgentDecisionException("阶段前置作品或精确版本确认尚未满足");
        if("plan-generation".equals(skill.id())&&!store.workspace(s).hasNonNull("executionPlanId"))return false;
        var source=input.hasNonNull("source")?input.get("source"):store.workspace(s).path("selection");
        if(artifact!=null) {
            if(!artifact.path("artifactId").equals(source.path("artifactId"))||!artifact.path("version").equals(source.path("version")))
                throw new InvalidAgentDecisionException("Skill source必须引用当前阶段已验证/确认的准确作品版本，不能替换成其他历史作品");
        }
        if(!stage.allowedSkills().contains(skill.id())||!Objects.equals(stage.outputType(),skill.resultType()))
            throw new InvalidAgentDecisionException("该Skill不属于当前Recipe阶段允许能力");
        var w=store.workspace(s);
        if(!w.hasNonNull("executionPlanId")||!stage.id().equals(r.planSteps().path(w.path("currentStepId").asText()).asText()))
            throw new InvalidAgentDecisionException("请先生成并采用符合当前技能阶段的计划，不得跨阶段执行");
        return !input.hasNonNull("artifactId") && !source.hasNonNull("sceneId") && artifact!=null && Set.of("SCRIPT","PROMPT").contains(skill.resultType())
                && skill.resultType().equals(artifact.path("type").asText());
    }
    public void validateComplete(Turn t) {
        var r=bound(t);if(r!=null&&!"COMPLETED".equals(r.status()))throw new InvalidAgentDecisionException("Recipe仍有未完成阶段，不能结束；请生成计划、执行当前能力或询问用户");
    }
    public void adopt(AgentStore store,Session s,AgentViews.Artifact plan) {
        var r=latest(s);if(r==null||Set.of("COMPLETED","CANCELLED").contains(r.status()))return;
        var stages=definition(r).stages().subList(r.stage(),definition(r).stages().size()).stream().filter(a->a.outputType()!=null).toList();
        var steps=plan.data().path("steps");
        if(!steps.isArray()||steps.size()!=stages.size())throw BusinessException.badRequest("计划必须覆盖本次技能剩余创作阶段，且不添加未经允许的步骤");
        var mapping=json.createObjectNode();
        for(int i=0;i<steps.size();i++) {
            if(!stages.get(i).outputType().equals(steps.get(i).path("kind").asText()))throw BusinessException.badRequest("计划步骤顺序或产物类型不符合当前技能阶段");
            mapping.put(steps.get(i).path("id").asText(),stages.get(i).id());
        }
        jdbc.update("UPDATE agent_recipe_run SET plan_steps_json=?,plan_id=?,version=version+1 WHERE id=?",write(mapping),store.workspace(s).path("executionPlanId").asText(null),r.id());
    }
    public void result(AgentStore store,Session s,Turn t,Call call,AgentViews.Artifact a) {
        var r=bound(t);if(r==null)return;
        if("PLAN".equals(a.type())) {update(r,"WAITING_PLAN","请查看并采用本次创作计划");return;}
        var stage=definition(r).stages().get(r.stage());
        var frozen=store.callContext(call.id());
        if(!matches(t,frozen.get("recipeRun"))||!stage.id().equals(r.planSteps().path(frozen.path("currentStepId").asText()).asText())||!stage.outputType().equals(a.type()))
            throw BusinessException.conflict("技能结果已不属于当前运行阶段");
        var results=r.results().deepCopy();results.set(stage.id(),json.createObjectNode().put("artifactId",a.id()).put("version",a.version()).put("type",a.type()));
        jdbc.update("UPDATE agent_recipe_run SET results_json=?,stage_index=stage_index+1,status='RUNNING',reason=NULL,version=version+1,updated_at=NOW() WHERE id=?",write(results),r.id());
    }
    public JsonNode view(AgentStore store,Session s) {
        var r=latest(s);if(r==null)return null;var d=definition(r);
        var out=json.createObjectNode().put("id",r.id()).put("recipeVersionId",r.versionId()).put("status",r.status()).put("version",r.version())
                .put("reason",r.reason()).put("decisionCount",r.decisions()).put("interactionCount",r.interactions());
        jdbc.query("SELECT recipe_id,version,name FROM creative_recipe_version WHERE id=?",row->{out.put("recipeId",row.getString(1)).put("recipeVersion",row.getInt(2)).put("name",row.getString(3));},r.versionId());
        out.set("variables",r.variables());out.set("fields",json.valueToTree(Set.of("WAITING_INPUT").contains(r.status())?fields(r):List.of()));
        if(r.stage()<d.stages().size()) {
            var stage=d.stages().get(r.stage());out.put("stageId",stage.id()).put("stageTitle",stage.title());
            if("WAITING_CONFIRMATION".equals(r.status())) {
                try{out.set("approvalArtifact",requiredArtifact(r,stage.requiresArtifactType(),store,s));}
                catch(BusinessException e){out.put("reason","前置作品已改变，请停止技能并重新评估；旧版本确认不可继续使用");}
            }
        } else out.putNull("stageId").put("stageTitle","已完成");
        return out;
    }
    public JsonNode context(Turn t) {
        var r=bound(t);if(r==null)return null;var d=definition(r);
        var out=json.createObjectNode().put("id",r.id()).put("epoch",r.epoch()).put("status",r.status()).put("instruction",d.instruction());
        out.put("goal",jdbc.queryForObject("SELECT goal FROM agent_recipe_run WHERE id=?",String.class,r.id()));
        out.set("variables",r.variables());out.set("results",r.results());out.set("approvals",r.approvals());
        if(r.stage()<d.stages().size())out.set("currentStage",json.valueToTree(d.stages().get(r.stage())));
        var stages=out.putArray("remainingStages");
        for(int i=r.stage();i<d.stages().size();i++){var stage=d.stages().get(i);stages.addObject().put("id",stage.id()).put("title",stage.title()).put("outputType",stage.outputType());}
        return out;
    }
    public void human(Turn t) {
        var r=bound(t);if(r==null)return;
        if(jdbc.update("UPDATE agent_recipe_run SET interaction_count=interaction_count+1,status='RUNNING',reason=NULL,version=version+1 WHERE id=? AND interaction_count<16 AND decision_count<64",r.id())!=1)
            throw BusinessException.conflict("本次技能已达到交互或决策预算，请停止并重新评估需求");
    }
    public void answer(AgentStore store,Session s,Run r,Map<String,String> values) {
        if(!"WAITING_INPUT".equals(r.status())||values==null||values.isEmpty()||values.size()>12)throw BusinessException.badRequest("当前没有待填写的技能信息");
        var fields=fields(r);var next=r.variables().deepCopy();
        for(var entry:values.entrySet()) {
            var field=fields.stream().filter(v->v.id().equals(entry.getKey())).findFirst().orElseThrow(()->BusinessException.badRequest("只能填写当前待确认字段"));
            String value=entry.getValue();if(value==null||value.isBlank()||value.length()>2000||!field.options().isEmpty()&&!field.options().contains(value))throw BusinessException.badRequest("字段内容为空、超长或不在提供的选项中");
            next.put(entry.getKey(),value.trim());
        }
        jdbc.update("UPDATE agent_recipe_run SET variables_json=?,status='RUNNING',reason=NULL,version=version+1 WHERE id=?",write(next),r.id());
    }
    public void approve(AgentStore store,Session s,Run r,String artifactId,int version) {
        if(!"WAITING_CONFIRMATION".equals(r.status()))throw BusinessException.conflict("当前没有待确认作品");
        var stage=definition(r).stages().get(r.stage());var ref=requiredArtifact(r,stage.requiresArtifactType(),store,s);
        if(ref==null||!artifactId.equals(ref.path("artifactId").asText())||version!=ref.path("version").asInt())throw BusinessException.conflict("作品确认版本已改变，请刷新");
        var next=r.approvals().deepCopy();next.set(stage.id(),ref);
        jdbc.update("UPDATE agent_recipe_run SET approvals_json=?,status='RUNNING',reason=NULL,version=version+1 WHERE id=?",write(next),r.id());
    }
    private ObjectNode read(String value) {try{return value==null?json.createObjectNode():(ObjectNode)json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static <T>T first(List<T> values){return values.isEmpty()?null:values.get(0);}
}
