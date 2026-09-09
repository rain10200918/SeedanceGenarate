package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.Objects;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** The caller holds the session/turn lock and the worker lease in the same short transaction. */
public final class AgentModelRecoveryStore {
    public static final int MAX_ATTEMPTS=3;
    private final JdbcTemplate jdbc;
    public AgentModelRecoveryStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Recovery(int attempts,String jobKey,long workspaceVersion,String recipe,
                           String status,LocalDateTime nextRetryAt,String callId,int truncationRepairs,int outputRepairs) {
        public Recovery(int attempts,String jobKey,long workspaceVersion,String recipe,String status,LocalDateTime nextRetryAt,String callId,int truncationRepairs) {
            this(attempts,jobKey,workspaceVersion,recipe,status,nextRetryAt,callId,truncationRepairs,0);
        }
    }
    private String phase(Call call) {return call==null?"DECISION":"TEXT_SKILL";}
    public Recovery get(Turn t,Call call) {
        var rows=jdbc.query("SELECT * FROM agent_model_recovery WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase=?",
                (r,n)->new Recovery(r.getInt("attempt_count"),r.getString("expected_job_key"),r.getLong("workspace_version"),
                        r.getString("recipe_json"),r.getString("status"),r.getTimestamp("next_retry_at")==null?null:r.getTimestamp("next_retry_at").toLocalDateTime(),r.getString("call_id"),r.getInt("truncation_repairs"),r.getInt("output_repairs")),
                t.id(),t.epoch(),t.step(),phase(call));
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean matches(AgentStore store,Session s,Turn t,Call call) {
        var r=get(t,call);if(r==null)return true;
        JsonNode frozen=r.recipe()==null?null:store.read(r.recipe());
        return r.workspaceVersion()==store.workspace(s).path("version").asLong()
                && Objects.equals(r.callId(),call==null?null:call.id())&&store.recipes().matches(t,frozen);
    }
    /** Count before external I/O. A process crash may consume an attempt, never reset its budget. */
    public int begin(AgentStore store,Session s,Turn t,Call call,String jobKey) {
        var r=get(t,call);
        if(r==null) {
            var recipe=store.recipes().freeze(t);
            jdbc.update("INSERT INTO agent_model_recovery(turn_id,execution_epoch,step_no,phase,call_id,expected_job_key,workspace_version,recipe_json) VALUES(?,?,?,?,?,?,?,?)",
                    t.id(),t.epoch(),t.step(),phase(call),call==null?null:call.id(),jobKey,store.workspace(s).path("version").asLong(),recipe==null?null:store.write(recipe));
        }
        jdbc.update("UPDATE agent_model_recovery SET attempt_count=attempt_count+1,status='RUNNING',next_retry_at=NULL,updated_at=NOW() WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase=?",
                t.id(),t.epoch(),t.step(),phase(call));
        return get(t,call).attempts();
    }
    public String defer(Turn t,Call call,String code,long delaySeconds) {
        var r=get(t,call);
        String key=(call==null?t.id()+":"+t.epoch()+":"+t.step():call.id())+":model-retry:"+r.attempts();
        return defer(t,call,code,delaySeconds,key);
    }
    /** Reserve in the same session-locked transaction as the deferred job; timeouts do not reset this count. */
    public boolean reserveOutputRepair(Turn t,Call call) {
        if(call==null)return false;
        return jdbc.update("UPDATE agent_model_recovery SET output_repairs=output_repairs+1 WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase='TEXT_SKILL' AND call_id=? AND output_repairs=0 AND status='RUNNING'",
                t.id(),t.epoch(),t.step(),call.id())==1;
    }
    /** Scene identity cannot be reused after the successful-scene attempt budget is reset. */
    public String deferPreparedScene(Turn t,Call call,int ordinal,String code,long delaySeconds) {
        return defer(t,call,code,delaySeconds,call.id()+":prepare:"+ordinal+":repair:1");
    }
    private String defer(Turn t,Call call,String code,long delaySeconds,String key) {
        jdbc.update("UPDATE agent_model_recovery SET expected_job_key=?,status='WAITING_RETRY',next_retry_at=TIMESTAMPADD(SECOND,?,NOW()),error_code=?,truncation_repairs=truncation_repairs+?,updated_at=NOW() WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase=?",
                key,delaySeconds,code,"MODEL_OUTPUT_TRUNCATED".equals(code)?1:0,t.id(),t.epoch(),t.step(),phase(call));
        return key;
    }
    public void finish(Turn t,Call call,String status,String code) {
        jdbc.update("UPDATE agent_model_recovery SET status=?,error_code=?,next_retry_at=NULL,updated_at=NOW() WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase=?",
                status,code,t.id(),t.epoch(),t.step(),phase(call));
    }
    /** Only after a validated scene checkpoint is committed in the same fenced transaction. */
    public void nextPreparedScene(Turn t,Call call) {
        jdbc.update("DELETE FROM agent_model_recovery WHERE turn_id=? AND execution_epoch=? AND step_no=? AND phase=? AND call_id=?",
                t.id(),t.epoch(),t.step(),phase(call),call.id());
    }
}
