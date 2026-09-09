package org.example.seedancegenarate.agent.persistence;

import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.*;
import org.example.seedancegenarate.agent.generation.VideoPreparationException;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Caller owns the session lock and current worker lease in the same short transaction; no model I/O here. */
public final class AgentVideoPromptCheckpointStore {
    private final JdbcTemplate jdbc;
    public AgentVideoPromptCheckpointStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,String> loadOrCreate(Session session,Turn turn,Call call,Plan plan) {
        current(session,turn,call);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_video_prompt_checkpoint WHERE call_id=?",Integer.class,call.id());
        if(count==0) {
            var reusable=new LinkedHashMap<String,String>();
            if(plan.batchStepId()!=null&&!plan.batchStepId().isBlank())
                jdbc.query("SELECT p.scene_key,p.prompt FROM agent_video_prompt_checkpoint p JOIN agent_turn t ON t.id=p.turn_id AND t.epoch=p.execution_epoch "
                        +"WHERE p.session_id=? AND p.plan_step_id=? AND p.execution_epoch=? AND p.binding_hash=? AND p.prompt IS NOT NULL "
                        +"AND t.status<>'CANCELLED' AND NOT EXISTS(SELECT 1 FROM agent_approval a WHERE a.call_id=p.call_id) ORDER BY p.created_at DESC,p.call_id",
                        r->{reusable.putIfAbsent(r.getString(1),r.getString(2));},session.id(),plan.batchStepId(),turn.epoch(),plan.bindingHash());
            for(var scene:plan.scenes())jdbc.update("INSERT INTO agent_video_prompt_checkpoint(call_id,scene_key,session_id,turn_id,execution_epoch,plan_step_id,binding_hash,ordinal,prompt) VALUES(?,?,?,?,?,?,?,?,?)",
                    call.id(),scene.key(),session.id(),turn.id(),turn.epoch(),plan.batchStepId(),plan.bindingHash(),scene.ordinal(),reusable.get(scene.key()));
        }
        var saved=new LinkedHashMap<String,String>();var keys=new HashSet<String>();
        jdbc.query("SELECT * FROM agent_video_prompt_checkpoint WHERE call_id=? ORDER BY ordinal",r->{
            if(!Objects.equals(session.id(),r.getString("session_id"))||!Objects.equals(turn.id(),r.getString("turn_id"))
                    ||turn.epoch()!=r.getLong("execution_epoch")||!Objects.equals(plan.bindingHash(),r.getString("binding_hash"))
                    ||!Objects.equals(plan.batchStepId(),r.getString("plan_step_id")))throw stale();
            String key=r.getString("scene_key");keys.add(key);String prompt=r.getString("prompt");if(prompt!=null)saved.put(key,prompt);
        },call.id());
        if(!keys.equals(new HashSet<>(plan.scenes().stream().map(Scene::key).toList())))throw stale();
        return saved;
    }
    /** Only the validated current scene result may fill an empty checkpoint; replay cannot overwrite it. */
    public void save(Session session,Turn turn,Call call,Plan plan,Scene scene,String prompt) {
        current(session,turn,call);
        if(!plan.scenes().contains(scene)||prompt==null||prompt.isBlank()||prompt.length()>plan.perPrompt())throw stale();
        int changed=jdbc.update("UPDATE agent_video_prompt_checkpoint SET prompt=?,updated_at=NOW() WHERE call_id=? AND scene_key=? AND session_id=? AND turn_id=? AND execution_epoch=? AND binding_hash=? AND prompt IS NULL",
                prompt,call.id(),scene.key(),session.id(),turn.id(),turn.epoch(),plan.bindingHash());
        if(changed==0) {
            var saved=loadOrCreate(session,turn,call,plan);
            if(!Objects.equals(prompt,saved.get(scene.key())))throw stale();
        }
    }
    /** A completed scene advances the expected durable job identity even after model recovery is cleared. */
    public String nextJobKey(Call call) {
        int done=jdbc.queryForObject("SELECT COUNT(*) FROM agent_video_prompt_checkpoint WHERE call_id=? AND prompt IS NOT NULL",Integer.class,call.id());
        return done==0?call.id():call.id()+":prepare:"+done;
    }
    /** Safe validation metadata only; model originals live exclusively in opt-in local diagnostics. */
    public void recordFailure(Session session,Turn turn,Call call,VideoPreparationException failure) {
        current(session,turn,call);
        jdbc.update("UPDATE agent_video_prompt_checkpoint SET validation_code=?,validation_detail=?,diagnostic_id=?,updated_at=NOW() "
                        +"WHERE call_id=? AND session_id=? AND turn_id=? AND execution_epoch=? AND ordinal=? AND prompt IS NULL",
                failure.validationCode(),failure.validationDetail(),failure.diagnosticId(),call.id(),session.id(),turn.id(),turn.epoch(),failure.sceneOrdinal());
    }
    /** Consume before scheduling, inside the same transaction as the durable retry job. */
    public boolean reserveRepair(Session session,Turn turn,Call call,int ordinal) {
        current(session,turn,call);
        return jdbc.update("UPDATE agent_video_prompt_checkpoint SET repair_count=1,updated_at=NOW() "
                        +"WHERE call_id=? AND session_id=? AND turn_id=? AND execution_epoch=? AND ordinal=? "
                        +"AND prompt IS NULL AND repair_count=0 AND validation_code IS NOT NULL",
                call.id(),session.id(),turn.id(),turn.epoch(),ordinal)==1;
    }
    /** Only trusted validation instructions, never prior model output, enter the next invocation. */
    public String repairHint(Call call,Scene scene) {
        var hints=jdbc.query("SELECT validation_code FROM agent_video_prompt_checkpoint "
                        +"WHERE call_id=? AND scene_key=? AND prompt IS NULL AND repair_count=1",
                (r,n)->r.getString(1),call.id(),scene.key());
        return hints.isEmpty()?null:VideoPreparationException.ValidationRule.valueOf(hints.get(0)).repairHint();
    }
    public Map<String,Integer> progress(Turn turn) {
        var rows=jdbc.query("SELECT COUNT(*) AS total,SUM(CASE WHEN p.prompt IS NOT NULL THEN 1 ELSE 0 END) AS completed "
                +"FROM agent_video_prompt_checkpoint p JOIN agent_skill_call c ON c.id=p.call_id WHERE c.turn_id=? AND c.epoch=? AND c.step_no=? "
                +"AND c.skill_id='video-generation' AND c.status IN ('READY','RUNNING','WAITING_RETRY','SUSPENDED','FAILED') GROUP BY p.call_id",
                (r,n)->Map.of("total",r.getInt("total"),"completed",r.getInt("completed")),turn.id(),turn.epoch(),turn.step());
        return rows.isEmpty()?null:rows.get(0);
    }
    private void current(Session session,Turn turn,Call call) {
        int valid=jdbc.queryForObject("SELECT COUNT(*) FROM agent_session s JOIN agent_turn t ON t.session_id=s.id AND t.id=s.active_turn_id "
                +"JOIN agent_skill_call c ON c.turn_id=t.id AND c.epoch=t.epoch AND c.step_no=t.step_no "
                +"WHERE s.id=? AND s.user_id=? AND t.id=? AND t.epoch=? AND c.id=? AND c.skill_id='video-generation' "
                +"AND t.status IN ('RUNNING','QUEUED','WAITING_SKILL','WAITING_RETRY') AND c.status IN ('READY','RUNNING','WAITING_RETRY') "
                +"AND NOT EXISTS(SELECT 1 FROM agent_approval a WHERE a.call_id=c.id)",Integer.class,
                session.id(),session.userId(),turn.id(),turn.epoch(),call.id());
        if(valid!=1)throw stale();
    }
    private BusinessException stale() {return BusinessException.conflict("视频提示词准备条件已改变，请确认当前分镜和生成设置后继续");}
}
