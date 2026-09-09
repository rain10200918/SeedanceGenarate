package org.example.seedancegenarate.agent.application;

import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Explicit metadata projection: no execution service or JSON payload is loaded. */
@Service
public class AgentDebugQuery {
    private final JdbcTemplate db;
    public AgentDebugQuery(JdbcTemplate db) { this.db=db; }
    public record Page<T>(List<T> items, boolean truncated) {}
    public record Session(String id,long revision,String activeTurnId,String activeRecipeRunId) {}
    public record Turn(String id,String status,int stepNo,long epoch,String channel,String triggerType,
                       String parentTurnId,Long turnSeq,int resumeCount,String createdAt,String updatedAt) {}
    public record Decision(String id,String turnId,long epoch,int stepNo,String createdAt) {}
    public record Plan(String id,String turnId,Long executionEpoch,String status,String artifactId,int artifactVersion,String updatedAt) {}
    public record Step(String id,String planId,String stepKey,int ordinalNo,String kind,String status,
                       String skillId,String skillCallId,String errorCode,String updatedAt) {}
    public record Call(String id,String turnId,String skillId,String skillVersion,String status,long epoch,int stepNo,String createdAt) {}
    public record Task(String approvalId,String callId,String turnId,String taskId,String approvalStatus,String status,
                       String outputType,String provider,String updatedAt) {}
    public record Recovery(String turnId,long epoch,int stepNo,String phase,String status,int attemptCount,
                           String errorCode,String nextRetryAt,String updatedAt) {}
    public record Diagnostic(String conversationId,Session session,int limit,Page<Turn> turns,Page<Decision> decisions,
                             Page<Plan> plans,Page<Step> steps,Page<Call> calls,Page<Task> tasks,Page<Recovery> recoveries) {}
    private record Scope(Session session,long owner) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Diagnostic read(long conversationId,int limit) {
        if(conversationId<1 || limit<1 || limit>50) throw BusinessException.badRequest("诊断查询参数不合法");
        var scopes=db.query("SELECT s.id,s.revision,s.active_turn_id,s.active_recipe_run_id,s.user_id FROM agent_session s "
                +"JOIN conversation c ON c.id=s.conversation_id AND c.user_id=s.user_id "
                +"WHERE s.conversation_id=? AND c.archived=0 AND c.creation_mode='AGENT'",
                (r,n)->new Scope(new Session(r.getString("id"),r.getLong("revision"),r.getString("active_turn_id"),r.getString("active_recipe_run_id")),r.getLong("user_id")),conversationId);
        if(scopes.isEmpty()) throw BusinessException.notFound("Agent对话不存在");
        var scope=scopes.get(0); String session=scope.session().id(); int size=limit+1;
        var turns=db.query("SELECT id,status,step_no,epoch,channel,trigger_type,parent_turn_id,turn_seq,resume_count,created_at,updated_at "
                +"FROM agent_turn WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                (r,n)->new Turn(r.getString("id"),r.getString("status"),r.getInt("step_no"),r.getLong("epoch"),r.getString("channel"),
                        r.getString("trigger_type"),r.getString("parent_turn_id"),(Long)r.getObject("turn_seq"),r.getInt("resume_count"),time(r,"created_at"),time(r,"updated_at")),session,size);
        var decisions=db.query("SELECT d.id,d.turn_id,d.epoch,d.step_no,d.created_at FROM agent_decision d "
                +"JOIN agent_turn t ON t.id COLLATE utf8mb4_unicode_ci=d.turn_id COLLATE utf8mb4_unicode_ci "
                +"WHERE t.session_id=? ORDER BY d.created_at DESC,d.id DESC LIMIT ?",
                (r,n)->new Decision(r.getString("id"),r.getString("turn_id"),r.getLong("epoch"),r.getInt("step_no"),time(r,"created_at")),session,size);
        var plans=db.query("SELECT id,turn_id,execution_epoch,status,artifact_id,artifact_version,updated_at "
                +"FROM agent_plan WHERE session_id=? ORDER BY updated_at DESC,id DESC LIMIT ?",
                (r,n)->new Plan(r.getString("id"),r.getString("turn_id"),(Long)r.getObject("execution_epoch"),r.getString("status"),
                        r.getString("artifact_id"),r.getInt("artifact_version"),time(r,"updated_at")),session,size);
        var steps=db.query("SELECT s.id,s.plan_id,s.step_key,s.ordinal_no,s.kind,s.status,s.skill_id,s.skill_call_id,s.error_code,s.updated_at "
                +"FROM agent_plan_step s JOIN agent_plan p ON p.id COLLATE utf8mb4_unicode_ci=s.plan_id COLLATE utf8mb4_unicode_ci "
                +"WHERE p.session_id=? ORDER BY s.updated_at DESC,s.id DESC LIMIT ?",
                (r,n)->new Step(r.getString("id"),r.getString("plan_id"),r.getString("step_key"),r.getInt("ordinal_no"),r.getString("kind"),
                        r.getString("status"),r.getString("skill_id"),r.getString("skill_call_id"),code(r.getString("error_code")),time(r,"updated_at")),session,size);
        var calls=db.query("SELECT c.id,c.turn_id,c.skill_id,c.skill_version,c.status,c.epoch,c.step_no,c.created_at "
                +"FROM agent_skill_call c JOIN agent_turn t ON t.id COLLATE utf8mb4_unicode_ci=c.turn_id COLLATE utf8mb4_unicode_ci "
                +"WHERE t.session_id=? ORDER BY c.created_at DESC,c.id DESC LIMIT ?",
                (r,n)->new Call(r.getString("id"),r.getString("turn_id"),r.getString("skill_id"),r.getString("skill_version"),r.getString("status"),
                        r.getLong("epoch"),r.getInt("step_no"),time(r,"created_at")),session,size);
        var tasks=db.query("SELECT a.id,a.call_id,a.turn_id,a.task_id,a.status AS approval_status,v.status AS task_status,v.output_type,v.provider,"
                +"COALESCE(v.update_time,a.updated_at) AS updated_at FROM agent_approval a "
                +"LEFT JOIN video_task v ON v.task_id COLLATE utf8mb4_unicode_ci=a.task_id COLLATE utf8mb4_unicode_ci AND v.user_id=? "
                +"WHERE a.session_id=? ORDER BY a.updated_at DESC,a.id DESC LIMIT ?",
                (r,n)->new Task(r.getString("id"),r.getString("call_id"),r.getString("turn_id"),r.getString("task_id"),r.getString("approval_status"),
                        r.getString("task_status"),r.getString("output_type"),r.getString("provider"),time(r,"updated_at")),scope.owner(),session,size);
        var recoveries=db.query("SELECT r.turn_id,r.execution_epoch,r.step_no,r.phase,r.status,r.attempt_count,r.error_code,r.next_retry_at,r.updated_at "
                +"FROM agent_model_recovery r JOIN agent_turn t ON t.id COLLATE utf8mb4_unicode_ci=r.turn_id COLLATE utf8mb4_unicode_ci "
                +"WHERE t.session_id=? ORDER BY r.updated_at DESC,r.turn_id DESC,r.execution_epoch DESC,r.step_no DESC,r.phase DESC LIMIT ?",
                (r,n)->new Recovery(r.getString("turn_id"),r.getLong("execution_epoch"),r.getInt("step_no"),r.getString("phase"),r.getString("status"),
                        r.getInt("attempt_count"),code(r.getString("error_code")),time(r,"next_retry_at"),time(r,"updated_at")),session,size);
        return new Diagnostic(Long.toString(conversationId),scope.session(),limit,page(turns,limit),page(decisions,limit),page(plans,limit),
                page(steps,limit),page(calls,limit),page(tasks,limit),page(recoveries,limit));
    }
    private static String time(ResultSet row,String name) throws SQLException {
        var value=row.getTimestamp(name); return value==null?null:value.toLocalDateTime().toString();
    }
    private static String code(String value) {
        return value==null?null:value.matches("[A-Z][A-Z0-9_]{0,63}")?value:"UNKNOWN";
    }
    private static <T> Page<T> page(List<T> values,int limit) {
        return new Page<>(List.copyOf(values.subList(0,Math.min(limit,values.size()))), values.size()>limit);
    }
}
