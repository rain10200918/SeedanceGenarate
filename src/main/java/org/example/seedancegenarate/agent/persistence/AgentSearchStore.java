package org.example.seedancegenarate.agent.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.example.seedancegenarate.agent.skill.SearchProvider;
import java.time.LocalDateTime;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Caller owns the Session lock and transaction. Budget reservation commits before network I/O. */
public final class AgentSearchStore {
    public static final int MAX_REQUESTS=3;
    private final JdbcTemplate jdbc;
    public AgentSearchStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Attempt(int count,String scope,String status,String jobKey,LocalDateTime retryAt) {}
    public Attempt get(Call call) {
        var rows=jdbc.query("SELECT * FROM agent_search_attempt WHERE call_id=?",(r,n)->new Attempt(r.getInt("attempt_count"),
                r.getString("scope_key"),r.getString("status"),r.getString("expected_job_key"),
                r.getTimestamp("next_retry_at")==null?null:r.getTimestamp("next_retry_at").toLocalDateTime()),call.id());
        return rows.isEmpty()?null:rows.get(0);
    }
    public int used(String scope) {return jdbc.queryForObject("SELECT COALESCE(SUM(attempt_count),0) FROM agent_search_attempt WHERE scope_key=?",Integer.class,scope);}
    public boolean begin(AgentStore store,Session session,Turn turn,Call call,String jobKey) {
        var previous=get(call);
        if(previous!=null&&(!previous.jobKey().equals(jobKey)||!java.util.Set.of("RUNNING","WAITING_RETRY").contains(previous.status())))return false;
        if(previous!=null&&previous.retryAt()!=null&&previous.retryAt().isAfter(store.now()))return false;
        String plan=store.callContext(call.id()).path("executionPlanId").asText(null);
        String scope=plan==null?"session:"+session.id()+":"+turn.epoch():"plan:"+plan;
        if(used(scope)>=MAX_REQUESTS)throw new SearchProvider.Failure("SEARCH_BUDGET_EXHAUSTED",false);
        if(previous==null)jdbc.update("INSERT INTO agent_search_attempt(call_id,scope_key,status,expected_job_key) VALUES(?,?,'RUNNING',?)",call.id(),scope,jobKey);
        jdbc.update("UPDATE agent_search_attempt SET attempt_count=attempt_count+1,status='RUNNING',next_retry_at=NULL,updated_at=NOW() WHERE call_id=?",call.id());
        return true;
    }
    public String defer(Call call,String code,long seconds) {
        String key=call.id()+":search-retry:"+get(call).count();
        jdbc.update("UPDATE agent_search_attempt SET status='WAITING_RETRY',expected_job_key=?,next_retry_at=TIMESTAMPADD(SECOND,?,NOW()),error_code=?,updated_at=NOW() WHERE call_id=?",key,seconds,code,call.id());
        return key;
    }
    public void finish(Call call,String status,String code) {
        jdbc.update("UPDATE agent_search_attempt SET status=?,next_retry_at=NULL,error_code=?,updated_at=NOW() WHERE call_id=?",status,code,call.id());
    }
    public void observe(Turn turn,Call call,String code) {
        String detail="公开资料检索未完成；未采用未核实内容，未重新执行生成任务。";
        jdbc.update("INSERT INTO agent_observation(id,turn_id,execution_epoch,decision_seq,skill_call_id,type,code,detail) VALUES(?,?,?,?,?,'SEARCH_ERROR',?,?) ON DUPLICATE KEY UPDATE code=?,detail=?",
                java.util.UUID.randomUUID().toString(),turn.id(),turn.epoch(),turn.step(),call.id(),code,detail,code,detail);
    }
}
