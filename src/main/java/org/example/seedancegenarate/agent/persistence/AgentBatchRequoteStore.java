package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.Plan;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Expiry-only exception. Never used by ordinary resume or generic checkpoint reuse. */
public final class AgentBatchRequoteStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public AgentBatchRequoteStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public static BusinessException stale(){return BusinessException.conflict("原报价状态、规格或准备绑定已变化，请到分镜作品卡重新准备并确认新费用。");}
    public List<AgentBatchRuntime.Item> items(AgentBatchStore.Batch b) {
        return db.query("SELECT i.scene_id,i.ordinal_no,i.source_ref,a.quote_json FROM agent_batch_item i JOIN agent_approval a ON a.id=i.approval_id WHERE i.batch_id=? ORDER BY i.ordinal_no",(r,n)->{
            try{return new AgentBatchRuntime.Item(r.getString(1),r.getInt(2),json.readTree(r.getString(3)),json.readValue(r.getString(4),TaskQuote.class));}
            catch(Exception e){throw stale();}
        },b.id());
    }
    public List<String> requests(AgentBatchStore.Batch b) {
        return db.query("SELECT request_id FROM agent_approval WHERE grant_id=?",(r,n)->r.getString(1),b.id());
    }
    public boolean eligible(AgentStore store,Session s,AgentBatchStore.Batch b) {
        if(b==null||!"EXPIRED".equals(b.status())||!s.id().equals(b.session())||!b.turn().equals(s.activeTurnId()))return false;
        var t=store.turn(b.turn());var w=store.workspace(s);
        if(t==null||!"SUSPENDED".equals(t.status())||t.epoch()!=b.epoch()||t.step()!=b.step()
                ||w.path("version").asLong()!=b.workspace()||!w.path("executionPlanId").asText().equals(b.plan()))return false;
        if(db.queryForObject("SELECT COUNT(*) FROM agent_plan p JOIN agent_plan_step st ON st.plan_id=p.id WHERE p.id=? AND p.session_id=? AND p.turn_id=? AND p.execution_epoch=? AND p.status='SUSPENDED' AND st.id=? AND st.step_key=? AND st.scope='STORYBOARD_SCENES' AND st.status='SUSPENDED'",Integer.class,b.plan(),s.id(),b.turn(),b.epoch(),b.planStep(),w.path("currentStepId").asText())!=1)return false;
        if(db.queryForObject("SELECT COUNT(*) FROM agent_skill_call WHERE id=? AND turn_id=? AND epoch=? AND step_no=? AND status='CANCELLED'",Integer.class,b.parent(),b.turn(),b.epoch(),b.step())!=1)return false;
        int total=db.queryForObject("SELECT COUNT(*) FROM agent_batch_item WHERE batch_id=?",Integer.class,b.id());
        if(total<1||total>12||db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE grant_id=?",Integer.class,b.id())!=total
                ||!store.recipes().matches(t,store.callContext(b.parent()).get("recipeRun")))return false;
        // Bind request values rather than joining differently aged tables' string collations.
        var requests=requests(b);var args=new ArrayList<Object>();args.add(s.userId());args.addAll(requests);
        if(db.queryForObject("SELECT COUNT(*) FROM video_task WHERE user_id=? AND request_id IN ("+String.join(",",Collections.nCopies(requests.size(),"?"))+")",Integer.class,args.toArray())>0)return false;
        int safe=db.queryForObject("SELECT COUNT(*) FROM agent_batch_item i JOIN agent_approval a ON a.id=i.approval_id JOIN agent_skill_call c ON c.id=i.call_id JOIN agent_plan_scene sc ON sc.id=i.scene_id "
                +"WHERE i.batch_id=? AND a.grant_id=? AND a.session_id=? AND a.turn_id=? AND a.epoch=? AND a.step_no=? AND a.call_id=i.call_id AND a.status='CANCELLED' AND a.task_id IS NULL "
                +"AND c.turn_id=a.turn_id AND c.epoch=a.epoch AND c.step_no=a.step_no AND c.status='CANCELLED' AND sc.plan_step_id=? AND sc.skill_call_id=c.id AND sc.status='CANCELLED' AND sc.result_ref IS NULL AND sc.ordinal_no=i.ordinal_no",
                Integer.class,b.id(),b.id(),s.id(),b.turn(),b.epoch(),b.step(),b.planStep());
        if(safe!=total||db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED'",Integer.class,b.planStep())!=total)return false;
        var sources=db.query("SELECT i.source_ref,sc.source_ref FROM agent_batch_item i JOIN agent_plan_scene sc ON sc.id=i.scene_id WHERE i.batch_id=?",(r,n)->{
            try{return json.readTree(r.getString(1)).equals(json.readTree(r.getString(2)));}catch(Exception e){return false;}
        },b.id());
        if(sources.stream().anyMatch(equal->!equal))return false;
        return store.batches().intact(store,b);
    }
    /** Exact originating parent only, including ownership and complete prompt bytes. No fallback search. */
    public Map<String,String> prompts(AgentBatchStore.Batch b,Plan plan) {
        var result=new LinkedHashMap<String,String>();
        db.query("SELECT * FROM agent_video_prompt_checkpoint WHERE call_id=? ORDER BY ordinal",r->{
            if(!b.session().equals(r.getString("session_id"))||!b.turn().equals(r.getString("turn_id"))||b.epoch()!=r.getLong("execution_epoch")
                    ||!b.planStep().equals(r.getString("plan_step_id"))||!plan.bindingHash().equals(r.getString("binding_hash"))||r.getString("prompt")==null)throw stale();
            String key=r.getString("scene_key");
            int ordinal=r.getInt("ordinal");
            if(plan.scenes().stream().noneMatch(scene->scene.key().equals(key)&&scene.ordinal()==ordinal))throw stale();
            result.put(key,r.getString("prompt"));
        },b.parent());
        if(!result.keySet().equals(new HashSet<>(plan.scenes().stream().map(s->s.key()).toList())))throw stale();
        return result;
    }
    public String checkpointHash(AgentBatchStore.Batch b) {
        var hashes=db.query("SELECT DISTINCT binding_hash FROM agent_video_prompt_checkpoint WHERE call_id=?",(r,n)->r.getString(1),b.parent());
        if(hashes.size()!=1)throw stale();return hashes.get(0);
    }
    /** Caller holds session fence; inserts new identities without updating any old grant/call/approval. */
    public String create(AgentStore store,Session s,AgentBatchStore.Batch old,AgentBatchRuntime.Prepared quotes,Plan plan,Map<String,String> prompts) {
        if(!eligible(store,s,old))throw stale();
        var parent=store.call(old.parent());
        store.continueTurn(old.turn());var next=store.turn(old.turn());
        String callId=UUID.randomUUID().toString();var context=store.callContext(old.parent());
        db.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no,context_json,call_slot) VALUES(?,?,?,?,?,'WAITING_APPROVAL',?,?,?,0)",
                callId,next.id(),parent.skillId(),parent.skillVersion(),parent.input(),next.epoch(),next.step(),store.write(context));
        db.update("UPDATE agent_plan_step SET skill_call_id=? WHERE id=?",callId,old.planStep());
        var fresh=store.call(callId);
        if(plan!=null)for(var scene:plan.scenes())db.update("INSERT INTO agent_video_prompt_checkpoint(call_id,scene_key,session_id,turn_id,execution_epoch,plan_step_id,binding_hash,ordinal,prompt) VALUES(?,?,?,?,?,?,?,?,?)",
                callId,scene.key(),s.id(),next.id(),next.epoch(),old.planStep(),plan.bindingHash(),scene.ordinal(),prompts.get(scene.key()));
        return store.batches().create(store,s,next,fresh,quotes);
    }
}
