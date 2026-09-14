package org.example.seedancegenarate.agent.persistence;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** One approval is one immutable generation command and one eventual task binding. */
@Repository
@RequiredArgsConstructor
public class AgentApprovalStore {
    private final JdbcTemplate jdbc;
    private final AgentStore store;
    public Approval get(String id) {
        return get(id,false);
    }
    public Approval locked(String id) { return get(id,true); }
    /** Request-local display reads; mutations still use locked(id). */
    public Map<String,Approval> projectApprovals(Session s,Collection<String> ids) {
        var result=new HashMap<String,Approval>();
        if(ids.isEmpty())return result;
        var args=new ArrayList<Object>();args.add(s.id());args.addAll(new LinkedHashSet<>(ids));
        String marks=String.join(",",Collections.nCopies(args.size()-1,"?"));
        jdbc.query("SELECT * FROM agent_approval WHERE session_id=? AND id IN ("+marks+")",r->{
            var a=new Approval(r.getString("id"),r.getString("session_id"),r.getString("turn_id"),r.getString("call_id"),
                    r.getLong("epoch"),r.getInt("step_no"),r.getInt("version"),r.getString("status"),r.getString("quote_json"),
                    r.getString("request_id"),r.getString("task_id"),r.getString("error_message"),r.getTimestamp("expires_at").toLocalDateTime());
            result.put(a.id(),a);
        },args.toArray());
        return result;
    }
    public Map<String,com.fasterxml.jackson.databind.JsonNode> projectContexts(Session s,Collection<String> ids) {
        var result=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        if(ids.isEmpty())return result;
        var args=new ArrayList<Object>();args.add(s.id());args.addAll(new LinkedHashSet<>(ids));
        String marks=String.join(",",Collections.nCopies(args.size()-1,"?"));
        jdbc.query("SELECT c.id,c.context_json FROM agent_skill_call c JOIN agent_turn t ON t.id=c.turn_id "
                +"WHERE t.session_id=? AND c.id IN ("+marks+")",r->{
            String saved=r.getString(2);result.put(r.getString(1),store.read(saved==null?"{\"version\":0}":saved));
        },args.toArray());
        return result;
    }
    private Approval get(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM agent_approval WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Approval(r.getString("id"),r.getString("session_id"),
                r.getString("turn_id"),r.getString("call_id"),r.getLong("epoch"),r.getInt("step_no"),r.getInt("version"),r.getString("status"),
                r.getString("quote_json"),r.getString("request_id"),r.getString("task_id"),r.getString("error_message"),r.getTimestamp("expires_at").toLocalDateTime()),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public String create(Session s,Turn t,Call call,TaskQuote quote) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_approval(id,session_id,turn_id,call_id,epoch,step_no,status,quote_json,request_id,expires_at) "
                +"VALUES(?,?,?,?,?,?,'PENDING',?,?,TIMESTAMPADD(MINUTE,10,NOW()))",id,s.id(),t.id(),call.id(),t.epoch(),t.step(),store.write(quote),"agent:"+id);
        return id;
    }
    public void status(String id,String state,String error) {
        jdbc.update("UPDATE agent_approval SET status=?,error_message=?,updated_at=NOW(),next_check_at=TIMESTAMPADD(SECOND,30,NOW()) WHERE id=?",state,error,id);
    }
    public void answer(String id,String state) {
        jdbc.update("UPDATE agent_approval SET status=?,version=version+1,updated_at=NOW() WHERE id=?",state,id);
    }
    public void bind(String id,String task) {
        jdbc.update("UPDATE agent_approval SET task_id=?,status='ACCEPTED',updated_at=NOW(),next_check_at=NOW() WHERE id=?",task,id);
    }
    public void cancel(String turn) {
        jdbc.update("UPDATE agent_approval SET status='CANCELLED',version=version+1,updated_at=NOW() WHERE turn_id=? AND status IN ('PENDING','APPROVED')",turn);
    }
    public void postpone(String id) { jdbc.update("UPDATE agent_approval SET next_check_at=TIMESTAMPADD(SECOND,30,NOW()) WHERE id=?",id); }
    public List<String> due() {
        return jdbc.query("SELECT id FROM agent_approval WHERE status IN ('APPROVED','SUBMITTING','ACCEPTED') AND next_check_at<=NOW() ORDER BY next_check_at,id LIMIT 50",(r,n)->r.getString(1));
    }
    public List<String> expired() {
        return jdbc.query("SELECT id FROM agent_approval WHERE status='PENDING' AND expires_at<=NOW() ORDER BY expires_at LIMIT 50",(r,n)->r.getString(1));
    }
    public List<String> byTask(String task) { return jdbc.query("SELECT id FROM agent_approval WHERE task_id=? AND status='ACCEPTED'",(r,n)->r.getString(1),task); }
    public boolean hasTask(Session s) { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE session_id=? AND task_id IS NOT NULL",Long.class,s.id())>0; }
    public void artifact(Session s,Approval a,String mediaType,String title) {
        store.recordMedia(s,a,mediaType,title);
    }
}
