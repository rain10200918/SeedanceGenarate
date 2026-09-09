package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.skill.SkillResult;
import org.example.seedancegenarate.config.AgentRuntimeProperties;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Statement;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** All SQL uses the application's Writer datasource. Callers own the short transaction. */
@Repository
public class AgentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentRuntimeProperties limits;
    private final AgentPlanStore plans;
    private final AgentModelRecoveryStore modelRecovery;
    private final AgentBatchStore batches;
    private final org.example.seedancegenarate.agent.recipe.AgentRecipeRunStore recipes;
    public AgentStore(JdbcTemplate jdbc,ObjectMapper json) {
        this(jdbc,json,new AgentRuntimeProperties());
    }
    @Autowired
    public AgentStore(JdbcTemplate jdbc,ObjectMapper json,AgentRuntimeProperties limits) {
        this.jdbc=jdbc; this.json=json; this.limits=limits; this.plans=new AgentPlanStore(jdbc,json,limits);
        this.modelRecovery=new AgentModelRecoveryStore(jdbc);
        this.batches=new AgentBatchStore(jdbc,json,limits);
        this.recipes=new org.example.seedancegenarate.agent.recipe.AgentRecipeRunStore(jdbc,json);
    }
    public AgentPlanStore plans() { return plans; }
    public AgentSearchStore search() { return new AgentSearchStore(jdbc); }
    public AgentBatchStore batches() { return batches; }
    public AgentModelRecoveryStore modelRecovery() { return modelRecovery; }
    public AgentVideoPromptCheckpointStore videoCheckpoints() { return new AgentVideoPromptCheckpointStore(jdbc); }
    /** Safe diagnostic only; never modifies the frozen execution identity. */
    public void preparationError(Session s,Turn t,Call call,org.example.seedancegenarate.agent.generation.VideoPreparationException failure) {
        if(call==null)return;
        ObjectNode context=(ObjectNode)callContext(call.id()).deepCopy();
        ObjectNode error=json.createObjectNode().put("code",failure.code()).put("message",failure.getMessage())
                .put("workspaceVersion",context.path("version").asLong());
        if(failure.sceneOrdinal()!=null)error.put("sceneOrdinal",failure.sceneOrdinal());
        JsonNode source=failure.sourceRef()!=null?failure.sourceRef():context.get("sourceRef");
        if(source!=null&&!source.isNull()) {
            ObjectNode ref=(ObjectNode)source.deepCopy();
            if(failure.sourceRef()==null&&failure.sceneOrdinal()!=null) {
                var artifact=artifactVersion(s,ref.path("artifactId").asText(),ref.path("version").asInt());
                if(artifact!=null&&artifact.data()!=null) {
                    JsonNode scene=artifact.data().path("scenes").path(failure.sceneOrdinal()-1);
                    if(scene.hasNonNull("sceneId"))ref.put("sceneId",scene.path("sceneId").asText());
                }
            }
            error.set("sourceRef",ref);error.putArray("operations").add("VIEW_SOURCE");
        } else error.putArray("operations");
        context.set("actionableError",error);
        jdbc.update("UPDATE agent_skill_call SET context_json=? WHERE id=?",write(context),call.id());
    }
    public JsonNode actionableError(Session s,Turn t) {
        if(t==null||!Set.of("SUSPENDED","FAILED").contains(t.status()))return null;
        String saved=first(jdbc.query("SELECT context_json FROM agent_skill_call WHERE turn_id=? AND epoch=? AND step_no=? ORDER BY created_at DESC LIMIT 1",
                (r,n)->r.getString(1),t.id(),t.epoch(),t.step()));
        if(saved==null)return null;
        JsonNode error=read(saved).get("actionableError");
        return error!=null&&error.path("workspaceVersion").asLong()==workspace(s).path("version").asLong()?error:null;
    }
    public void renewModelDeadline(Turn t) {
        jdbc.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(MINUTE,15,NOW()),updated_at=NOW() WHERE id=? AND epoch=?",t.id(),t.epoch());
    }
    public org.example.seedancegenarate.agent.recipe.AgentRecipeRunStore recipes() { return recipes; }

    public Session owned(long conversationId, long owner, boolean lock) {
        return owned(conversationId,owner,lock,false);
    }
    /** Internal lifecycle only: deleted conversations still need paid-task reconciliation. */
    public Session ownedForLifecycle(long conversationId,long owner,boolean lock) {
        return owned(conversationId,owner,lock,true);
    }
    private Session owned(long conversationId,long owner,boolean lock,boolean includeArchived) {
        var rows = jdbc.query("SELECT s.* FROM agent_session s JOIN conversation c ON c.id=s.conversation_id "
                + "WHERE s.conversation_id=? AND s.user_id=? AND c.user_id=?"
                + (includeArchived ? "" : " AND c.archived=0")
                + (lock ? " FOR UPDATE" : ""), (r,n) -> session(r), conversationId,owner,owner);
        if (rows.isEmpty()) throw BusinessException.notFound("对话不存在");
        return rows.get(0);
    }
    /** Caller holds the session lock and has fenced its active turn before hiding the conversation. */
    public void archive(Session s) {
        if(jdbc.update("UPDATE conversation SET archived=1 WHERE id=? AND archived=0",s.conversationId())>0)
            jdbc.update("UPDATE agent_session SET active_turn_id=NULL,revision=revision+1,updated_at=NOW() WHERE id=?",s.id());
    }
    public Session session(String id) {
        return first(jdbc.query("SELECT * FROM agent_session WHERE id=?", (r,n) -> session(r), id));
    }
    private Session session(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Session(r.getString("id"),r.getLong("conversation_id"),r.getLong("user_id"),
                r.getLong("revision"),r.getString("goal"),r.getString("summary"),r.getString("active_turn_id"));
    }
    public boolean userExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?",Long.class,id) > 0;
    }
    public List<AgentViews.Conversation> list(long owner) {
        return jdbc.query("SELECT c.id,c.title,s.updated_at FROM conversation c JOIN agent_session s ON s.conversation_id=c.id "
                + "WHERE s.user_id=? AND c.user_id=? AND c.archived=0 ORDER BY s.updated_at DESC,c.id DESC LIMIT 100",
                (r,n)->new AgentViews.Conversation(r.getString(1),r.getString(2),r.getTimestamp(3).toLocalDateTime().toString()),owner,owner);
    }
    public long create(long owner,String title) {
        boolean automatic=title==null||title.isBlank();
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var p = c.prepareStatement("INSERT INTO conversation(user_id,title,title_source,message_count,archived,creation_mode) VALUES(?,?,?,0,0,'AGENT')", new String[]{"id"});
            p.setLong(1,owner); p.setString(2,automatic?"新的创作":title); p.setString(3,automatic?"AUTO":"USER"); return p;
        },key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("INSERT INTO agent_session(id,conversation_id,user_id) VALUES(?,?,?)",UUID.randomUUID().toString(),id,owner);
        return id;
    }
    public String title(Session s) {
        return jdbc.queryForObject("SELECT title FROM conversation WHERE id=?",String.class,s.conversationId());
    }
    public Turn turn(String id) {
        return turn(id,false);
    }
    public Turn lockedTurn(String id) { return turn(id,true); }
    private Turn turn(String id,boolean lock) {
        if(id==null) return null;
        return first(jdbc.query("SELECT * FROM agent_turn WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Turn(r.getString("id"),r.getString("session_id"),
                r.getString("channel"),r.getString("status"),r.getInt("step_no"),r.getLong("epoch"),
                r.getString("error_message"),r.getTimestamp("deadline_at").toLocalDateTime()),id));
    }
    public String newTurn(Session s,String channel,String goal) {
        String id=UUID.randomUUID().toString();
        long sequence=nextTurnSequence(s);
        jdbc.update("INSERT INTO agent_turn(id,session_id,channel,status,deadline_at,trigger_type,turn_seq) "
                +"VALUES(?,?,?,'QUEUED',TIMESTAMPADD(MINUTE,15,NOW()),'USER_MESSAGE',?)",id,s.id(),channel,sequence);
        jdbc.update("UPDATE agent_session SET active_turn_id=?,goal=COALESCE(goal,?) WHERE id=?",id,goal,s.id());
        if(!"__DIRECT__".equals(channel)) plans.bind(s,turn(id),workspace(s));
        return id;
    }
    private long nextTurnSequence(Session s) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(turn_seq),0)+1 FROM agent_turn WHERE session_id=?",Long.class,s.id());
    }
    public String modelBinding(Turn t) {
        return jdbc.queryForObject("SELECT model_binding FROM agent_turn WHERE id=?",String.class,t.id());
    }
    /** Caller holds the session/Turn lock. Legacy turns bind once; never overwrite an existing identity. */
    public String bindModel(Turn t,String binding) {
        if(binding==null || !binding.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid model binding");
        jdbc.update("UPDATE agent_turn SET model_binding=? WHERE id=? AND epoch=? AND model_binding IS NULL",binding,t.id(),t.epoch());
        return modelBinding(t);
    }
    /** Caller owns the session lock and transaction; empty means a stale/replayed old Turn. */
    public Optional<Turn> yieldToSystemContinue(Session s,Turn current) {
        if(current==null||!current.id().equals(s.activeTurnId())) return Optional.empty();
        if(jdbc.update("UPDATE agent_turn SET status='YIELDED',yield_reason='DECISION_BUDGET',error_message=NULL,updated_at=NOW() "
                +"WHERE id=? AND session_id=? AND epoch=? AND step_no=? AND status IN ('QUEUED','RUNNING','WAITING_RETRY')",
                current.id(),s.id(),current.epoch(),current.step())!=1) return Optional.empty();
        int humanResumes=jdbc.queryForObject("SELECT resume_count FROM agent_turn WHERE id=?",Integer.class,current.id());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_turn(id,session_id,channel,status,step_no,epoch,deadline_at,resume_count,budget_start,trigger_type,parent_turn_id,turn_seq) "
                        +"VALUES(?,?,?,'QUEUED',0,?,TIMESTAMPADD(MINUTE,15,NOW()),?,0,'SYSTEM_CONTINUE',?,?)",
                id,s.id(),current.channel(),current.epoch(),humanResumes,current.id(),nextTurnSequence(s));
        jdbc.update("UPDATE agent_turn SET model_binding=? WHERE id=?",modelBinding(current),id);
        if(jdbc.update("UPDATE agent_session SET active_turn_id=? WHERE id=? AND active_turn_id=?",id,s.id(),current.id())!=1)
            throw new ConcurrencyFailureException("Agent active Turn changed during yield");
        Turn child=turn(id);
        plans.rebindForSystemContinue(current,child);
        jdbc.update("UPDATE agent_recipe_run SET turn_id=?,status='RUNNING',reason=NULL,version=version+1,updated_at=NOW() "
                +"WHERE id=(SELECT active_recipe_run_id FROM agent_session WHERE id=?) AND turn_id=? "
                +"AND status NOT IN ('COMPLETED','CANCELLED')",child.id(),s.id(),current.id());
        return Optional.of(child);
    }
    public String newDirectTurn(Session s,String prompt) {
        return newTurn(s,"__DIRECT__",prompt);
    }
    public boolean isDirectTurn(Turn turn) {
        String context=first(jdbc.query("SELECT context_json FROM agent_skill_call WHERE turn_id=? AND skill_id='direct-generation' LIMIT 1",
                (r,n)->r.getString(1),turn.id()));
        return context!=null && "DIRECT".equals(read(context).path("executionMode").asText());
    }
    public void status(String turn,String status,String error) {
        recipes.state(turn(turn),status,error);
        jdbc.update("UPDATE agent_turn SET status=?,error_message=?,updated_at=NOW() WHERE id=?",status,error,turn);
    }
    /** Explicit execution boundary. A failed slice pauses business progress; it never fails the Plan. */
    public void executionStatus(String turn,String status,String error) {
        plans.state(turn(turn),status,error);
        status(turn,status,error);
    }
    /** Caller holds the session lock and has committed the observed result in this transaction. */
    public Optional<Turn> advance(Session s,Turn expected) {
        Turn t=currentAdvance(s,expected);if(t==null)return Optional.empty();
        if(completePlanIfReady(s,t))return Optional.empty();
        if(!recipes.beforeStep(this,s,t,false))return Optional.empty();
        continueTurn(t.id());return Optional.of(turn(t.id()));
    }
    /** True means the final-result boundary handled this slice (completed or safely suspended). */
    public boolean completePlanIfReady(Session s,Turn expected) {
        Turn t=currentAdvance(s,expected);if(t==null)return false;
        String waiting=outstanding(t);
        if(waiting!=null){executionStatus(t.id(),waiting,null);return true;}
        var completion=plans.completion(s,t,workspace(s));
        if(completion==null)return false;
        if(!completion.valid()) {
            executionStatus(t.id(),"SUSPENDED","PLAN_OUTPUT_INCOMPLETE：计划结果关联不完整，已暂停并保留作品，请检查执行记录。");
            return true;
        }
        if(!recipes.beforeStep(this,s,t,false))return true;
        var recipe=recipes.bound(t);if(recipe!=null&&!"COMPLETED".equals(recipe.status()))return false;
        if(!plans.complete(t))return false;
        status(t.id(),"COMPLETED",null);
        String text="计划步骤已完成，已生成："+completion.summary()+"。";
        if(completion.video())text+="视频为独立片段，尚未合成为成片。";
        var parts=json.createArrayNode();parts.addObject().put("type","text").put("text",text);
        message(s,t.id(),"ASSISTANT",text,parts,null,null);
        return true;
    }
    private Turn currentAdvance(Session s,Turn expected) {
        Session latest=ownedForLifecycle(s.conversationId(),s.userId(),true);
        Turn t=lockedTurn(expected.id());
        return t!=null&&t.id().equals(latest.activeTurnId())&&t.sessionId().equals(s.id())&&t.epoch()==expected.epoch()&&t.step()==expected.step()
                &&Set.of("RUNNING","QUEUED","WAITING_SKILL","WAITING_TASK","WAITING_RETRY").contains(t.status())?t:null;
    }
    private String outstanding(Turn t) {
        var approvals=jdbc.query("SELECT status FROM agent_approval WHERE turn_id=? AND epoch=? AND status IN ('PENDING','BATCH_PENDING','BATCH_QUEUED','APPROVED','SUBMITTING','ACCEPTED')",(r,n)->r.getString(1),t.id(),t.epoch());
        if(!approvals.isEmpty())return approvals.stream().anyMatch(a->Set.of("APPROVED","SUBMITTING","ACCEPTED","BATCH_QUEUED").contains(a))?"WAITING_TASK":"WAITING_APPROVAL";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM agent_interaction WHERE turn_id=? AND epoch=? AND status='PENDING'",Long.class,t.id(),t.epoch())>0)return "WAITING_USER";
        var calls=jdbc.query("SELECT status FROM agent_skill_call WHERE turn_id=? AND epoch=? AND status IN ('READY','RUNNING','WAITING_RETRY','WAITING_APPROVAL','APPROVED','BATCH_QUEUED','SUBMITTING','WAITING_TASK')",(r,n)->r.getString(1),t.id(),t.epoch());
        if(calls.isEmpty())return null;
        if(calls.contains("WAITING_RETRY"))return "WAITING_RETRY";
        if(calls.contains("WAITING_APPROVAL"))return "WAITING_APPROVAL";
        return calls.stream().anyMatch(a->Set.of("APPROVED","BATCH_QUEUED","SUBMITTING","WAITING_TASK").contains(a))?"WAITING_TASK":"WAITING_SKILL";
    }
    public void continueTurn(String turn) {
        plans.state(turn(turn),"QUEUED",null);
        jdbc.update("UPDATE agent_turn SET step_no=step_no+1,status='QUEUED',error_message=NULL,deadline_at=TIMESTAMPADD(MINUTE,15,NOW()),updated_at=NOW() WHERE id=?",turn);
    }
    public boolean decisionBudgetExhausted(Turn t) {
        return t.step()>=limits.getMaxDecisionsPerTurn();
    }
    /** Human interaction grants one bounded continuation, not an automatic fresh turn. */
    public void resumeHuman(Turn t) {
        recipes.human(t);
        if(jdbc.update("UPDATE agent_turn SET resume_count=resume_count+1,budget_start=step_no+1 WHERE id=? AND resume_count<?",t.id(),limits.getMaxHumanResumes())!=1)
            throw BusinessException.conflict("本轮已达到恢复次数上限，请停止后重新采用计划");
        continueTurn(t.id());
    }
    public void cancel(Turn turn) {
        batches.cancel(turn.id());
        plans.state(turn,"CANCELLED",null);
        jdbc.update("UPDATE agent_turn SET status='CANCELLED',epoch=epoch+1,updated_at=NOW() WHERE id=?",turn.id());
        jdbc.update("UPDATE agent_model_recovery SET status='CANCELLED',next_retry_at=NULL,updated_at=NOW() WHERE turn_id=? AND status IN ('RUNNING','WAITING_RETRY','SUSPENDED')",turn.id());
        jdbc.update("UPDATE agent_skill_call SET status='CANCELLED' WHERE turn_id=? AND status IN ('READY','RUNNING','WAITING_RETRY','SUSPENDED','WAITING_APPROVAL','APPROVED')",turn.id());
        jdbc.update("UPDATE agent_interaction SET status='EXPIRED' WHERE turn_id=? AND status='PENDING'",turn.id());
    }
    public void touch(Session s) {
        jdbc.update("UPDATE agent_session SET revision=revision+1,updated_at=NOW() WHERE id=?",s.id());
        jdbc.update("UPDATE conversation SET last_message_at=NOW() WHERE id=?",s.conversationId());
    }
    public void summary(Session s,String summary) {
        if(summary!=null && !summary.isBlank()) jdbc.update("UPDATE agent_session SET summary=? WHERE id=?",summary,s.id());
    }
    public String requestHash(Session s,String request) {
        return first(jdbc.query("SELECT agent_request_hash FROM conversation_message WHERE conversation_id=? AND client_msg_id=?",
                (r,n)->r.getString(1),s.conversationId(),request));
    }
    public void message(Session s,String turn,String role,String content,JsonNode parts,String request,String hash) {
        jdbc.update("UPDATE conversation SET message_count=message_count+1 WHERE id=?",s.conversationId());
        Long seq=jdbc.queryForObject("SELECT message_count FROM conversation WHERE id=?",Long.class,s.conversationId());
        jdbc.update("INSERT INTO conversation_message(conversation_id,user_id,seq,role,kind,content,parts,agent_turn_id,client_msg_id,agent_request_hash) "
                + "VALUES(?,?,?,?,'PARTS',?,?,?,?,?)",s.conversationId(),s.userId(),seq,role,content,write(parts),turn,request,hash);
        if("USER".equals(role) && content!=null && !content.isBlank()) {
            String title=content.replaceAll("(?U)\\s+"," ").strip();
            if(title.codePointCount(0,title.length())>32) title=title.substring(0,title.offsetByCodePoints(0,32));
            // Same transaction/row lock as the message. Only the first user message can name it.
            jdbc.update("UPDATE conversation SET title=? WHERE id=? AND user_id=? AND title_source='AUTO' "
                    + "AND title='新的创作' AND ?=(SELECT MIN(seq) FROM conversation_message WHERE conversation_id=? AND role='USER' AND content IS NOT NULL AND TRIM(content)<>'')",
                    title,s.conversationId(),s.userId(),seq,s.conversationId());
        }
    }
    public void decision(Turn t,JsonNode decision) {
        jdbc.update("INSERT INTO agent_decision(id,turn_id,epoch,step_no,payload) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),t.id(),t.epoch(),t.step(),write(decision));
    }
    public String newCall(Turn t,String skill,String version,JsonNode input) {
        var context=workspace(session(t.sessionId()));
        var scene=plans.currentScene(session(t.sessionId()),t);
        if(scene!=null) {context.put("sceneItemId",scene.path("id").asText());context.set("selection",scene.get("sourceRef"));}
        context.set("recipeRun",recipes.freeze(t));
        context.set("sourceRef",input.hasNonNull("source")?input.get("source"):context.get("selection"));
        if("web-search".equals(skill)) {context.putNull("sourceRef");context.putNull("selection");}
        return insertCall(t,skill,version,input,context);
    }
    public String newDirectCall(Turn t,JsonNode input,JsonNode sourceRef) {
        var context=workspace(session(t.sessionId()));
        context.put("executionMode","DIRECT").putNull("planRef").putNull("currentStepId");
        context.putArray("steps");
        context.set("sourceRef",sourceRef); context.set("selection",sourceRef);
        return insertCall(t,"direct-generation","1",input,context);
    }
    private String insertCall(Turn t,String skill,String version,JsonNode input,JsonNode context) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no,context_json) VALUES(?,?,?,?,?,'READY',?,?,?)",
                id,t.id(),skill,version,write(input),t.epoch(),t.step(),write(context));
        if(!"DIRECT".equals(context.path("executionMode").asText())) plans.attach(t,id,skill);
        return id;
    }
    public Call call(String id) {
        return first(jdbc.query("SELECT * FROM agent_skill_call WHERE id=? FOR UPDATE",(r,n)->new Call(r.getString("id"),r.getString("turn_id"),
                r.getString("skill_id"),r.getString("skill_version"),r.getString("input_json"),r.getString("status"),r.getLong("epoch"),r.getInt("step_no")),id));
    }
    public void callStatus(String id,String status,String error) {
        jdbc.update("UPDATE agent_skill_call SET status=?,error_message=? WHERE id=?",status,error,id);
    }
    public String newInteraction(Turn t,String question,JsonNode options) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_interaction(id,turn_id,epoch,status,question,options_json,expires_at) VALUES(?,?,?,'PENDING',?,?,TIMESTAMPADD(DAY,1,NOW()))",
                id,t.id(),t.epoch(),question,write(options)); return id;
    }
    public Interaction interaction(String id) {
        return first(jdbc.query("SELECT * FROM agent_interaction WHERE id=?",(r,n)->new Interaction(r.getString("id"),r.getString("turn_id"),
                r.getLong("epoch"),r.getInt("version"),r.getString("status"),r.getString("question"),r.getString("options_json"),r.getTimestamp("expires_at").toLocalDateTime()),id));
    }
    public Interaction pending(String turn) {
        String id=first(jdbc.query("SELECT id FROM agent_interaction WHERE turn_id=? AND status='PENDING' ORDER BY created_at DESC LIMIT 1",(r,n)->r.getString(1),turn));
        return id==null?null:interaction(id);
    }
    public List<Session> expiredWaitingSessions() {
        return jdbc.query("SELECT s.* FROM agent_session s JOIN agent_turn t ON t.id=s.active_turn_id JOIN agent_interaction i ON i.turn_id=t.id "
                +"WHERE t.status='WAITING_USER' AND i.status='PENDING' AND i.expires_at<=NOW() LIMIT 50",(r,n)->session(r));
    }
    public void expireInteraction(String id) {
        jdbc.update("UPDATE agent_interaction SET status='EXPIRED',version=version+1 WHERE id=? AND status='PENDING'",id);
    }
    public java.time.LocalDateTime now() {
        return jdbc.queryForObject("SELECT NOW()",java.time.LocalDateTime.class);
    }
    public void answer(Interaction i,String text) {
        if(jdbc.update("UPDATE agent_interaction SET status='ANSWERED',response_text=?,version=version+1 WHERE id=? AND status='PENDING' AND version=? AND expires_at>NOW()",
                text,i.id(),i.version())!=1) throw BusinessException.conflict("这个问题已处理或过期，请刷新对话");
    }
    public List<AgentViews.Artifact> artifacts(Session s) {
        var result=new LinkedHashMap<String,AgentViews.Artifact>();
        var w=workspace(s);
        for(String key:List.of("planRef","selection")) {
            var ref=w.path(key);
            if(ref.isObject()) { var a=artifactVersion(s,ref.path("artifactId").asText(),ref.path("version").asInt()); result.put(a.id()+":"+a.version(),a); }
        }
        // Pin only the current scene step's frozen storyboard, without initializing or advancing it.
        for(var step:w.path("steps")) {
            if(!step.path("id").equals(w.path("currentStepId")) || !"STORYBOARD_SCENES".equals(step.path("scope").asText()))continue;
            var ref=step.path("scenes").path(0).path("sourceRef");
            if(ref.isObject()) {var a=artifactVersion(s,ref.path("artifactId").asText(),ref.path("version").asInt());result.putIfAbsent(a.id()+":"+a.version(),a);}
            break;
        }
        for(var a:jdbc.query("SELECT * FROM agent_artifact_version WHERE session_id=? AND user_id=? ORDER BY id DESC LIMIT 20",
                (r,n)->artifactRow(r),s.id(),s.userId())) result.putIfAbsent(a.id()+":"+a.version(),a);
        return result.values().stream().limit(20).toList();
    }
    public AgentViews.ArtifactPage artifactPage(Session s,Long beforeId,int limit,String artifactId) {
        record Entry(long rowId,AgentViews.Artifact artifact) {}
        var args=new ArrayList<Object>(); args.add(s.id()); args.add(s.userId());
        String sql="SELECT * FROM agent_artifact_version WHERE session_id=? AND user_id=?";
        if(beforeId!=null) { sql+=" AND id<?"; args.add(beforeId); }
        if(artifactId!=null) { sql+=" AND artifact_id=?"; args.add(artifactId); }
        sql+=" ORDER BY id DESC LIMIT ?"; args.add(limit+1);
        var rows=jdbc.query(sql,(r,n)->new Entry(r.getLong("id"),artifactRow(r)),args.toArray());
        var returned=rows.stream().limit(limit).toList();
        String next=rows.size()>limit?Long.toString(returned.get(returned.size()-1).rowId()):null;
        return new AgentViews.ArtifactPage(returned.stream().map(Entry::artifact).toList(),next);
    }
    private AgentViews.Artifact artifactRow(java.sql.ResultSet r) throws java.sql.SQLException {
        return new AgentViews.Artifact(r.getString("artifact_id"),r.getInt("version_no"),r.getString("type"),r.getString("title"),r.getString("content"),r.getString("task_id"),
                nullableJson(r.getString("data_json")),nullableJson(r.getString("source_ref_json")),nullableJson(r.getString("plan_ref_json")),r.getString("step_id"));
    }
    private JsonNode nullableJson(String value) { return value==null?null:read(value); }
    public AgentViews.Artifact artifactVersion(Session s,String id,int version) {
        var a=first(jdbc.query("SELECT * FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=? AND version_no=?",
                (r,n)->artifactRow(r),s.id(),s.userId(),id,version));
        if(a==null) throw BusinessException.notFound("作品版本不存在"); return a;
    }
    public boolean latestArtifact(Session s,String id,int version) {
        Integer latest=jdbc.queryForObject("SELECT MAX(version_no) FROM agent_artifact_version WHERE session_id=? AND user_id=? AND artifact_id=?",Integer.class,s.id(),s.userId(),id);
        return latest!=null && latest==version;
    }
    public AgentViews.Artifact artifact(Session s,String call,String artifactId,String type,String title,String content) {
        String id=artifactId==null?UUID.randomUUID().toString():artifactId;
        Integer last=jdbc.queryForObject("SELECT MAX(version_no) FROM agent_artifact_version WHERE artifact_id=? AND session_id=? AND user_id=?",Integer.class,id,s.id(),s.userId());
        if(artifactId!=null && last==null) throw BusinessException.forbidden("作品不属于当前对话");
        int version=last==null?1:last+1;
        jdbc.update("INSERT INTO agent_artifact_version(artifact_id,version_no,session_id,user_id,type,title,content,source_call_id) VALUES(?,?,?,?,?,?,?,?)",
                id,version,s.id(),s.userId(),type,title,content,call);
        return new AgentViews.Artifact(id,version,type,title,content);
    }
    public ObjectNode workspace(Session s) {
        String saved=jdbc.queryForObject("SELECT workspace_json FROM agent_session WHERE id=?",String.class,s.id());
        if(saved!=null) return plans.project(s,(ObjectNode)read(saved));
        var w=json.createObjectNode().put("version",0).put("planConfirmed",false);
        w.putNull("planRef").putNull("selection").putNull("currentStepId"); w.putArray("steps"); return w;
    }
    public void saveWorkspace(Session s,JsonNode workspace) {
        JsonNode durable=workspace.deepCopy();
        if(durable instanceof ObjectNode object) {object.remove("planProgress");object.remove("imageInputs");object.remove("sceneEdit");}
        jdbc.update("UPDATE agent_session SET workspace_json=? WHERE id=?",write(durable),s.id());
    }
    public JsonNode callContext(String callId) {
        String saved=first(jdbc.query("SELECT context_json FROM agent_skill_call WHERE id=?",(r,n)->r.getString(1),callId));
        return saved==null?json.createObjectNode().put("version",0):read(saved);
    }
    public boolean workspaceMatches(Session s,Call call) { return workspace(s).path("version").asLong()==callContext(call.id()).path("version").asLong(); }
    public AgentViews.Artifact recordResult(Session s,Call call,SkillResult result) {
        JsonNode source=result.source()==null?null:json.valueToTree(result.source());
        if(source!=null) {
            var original=artifactVersion(s,source.path("artifactId").asText(),source.path("version").asInt());
            if(result.artifactId()!=null && (!result.artifactId().equals(original.id()) || !result.type().equals(original.type()) || !latestArtifact(s,original.id(),original.version())))
                throw BusinessException.conflict("作品已有更新，请选择最新版本后修改");
        } else if(result.artifactId()!=null) throw BusinessException.badRequest("修改作品必须引用准确版本");
        var a=artifact(s,call.id(),result.artifactId(),result.type(),result.title(),result.content());
        JsonNode context=callContext(call.id());
        attachMetadata(s,a,result.data(),source,context);
        a=artifactVersion(s,a.id(),a.version());
        if("PLAN".equals(a.type())) {
            var w=workspace(s); w.put("version",w.path("version").asLong()+1).put("planConfirmed",false);
            w.set("planRef",reference(a)); w.putNull("currentStepId");
            var steps=w.putArray("steps");
            for(var step:result.data().path("steps")) { ObjectNode item=step.deepCopy(); item.put("status","PENDING"); steps.add(item); }
            saveWorkspace(s,w);
        } else {
            if(!"WEB_RESEARCH".equals(a.type())||!"INSUFFICIENT_EVIDENCE".equals(result.data().path("status").asText()))advanceWorkspace(s,context,a);
            var w=workspace(s); var selected=w.path("selection");
            if(source!=null && a.id().equals(source.path("artifactId").asText())
                    && a.id().equals(selected.path("artifactId").asText()) && source.path("version").asInt()==selected.path("version").asInt()) {
                ((ObjectNode)selected).put("version",a.version()); w.put("version",w.path("version").asLong()+1); saveWorkspace(s,w);
            }
        }
        return a;
    }
    private ObjectNode reference(AgentViews.Artifact a) { return json.createObjectNode().put("artifactId",a.id()).put("version",a.version()); }
    private void attachMetadata(Session s,AgentViews.Artifact a,JsonNode data,JsonNode source,JsonNode context) {
        String stepId=null;
        for(var step:context.path("steps")) if(a.type().equals(step.path("kind").asText()) && (step.path("id").asText().equals(context.path("currentStepId").asText())
                || a.version()>1 && a.id().equals(step.path("artifactRef").path("artifactId").asText()))) stepId=step.path("id").asText();
        jdbc.update("UPDATE agent_artifact_version SET data_json=?,source_ref_json=?,plan_ref_json=?,step_id=? WHERE session_id=? AND artifact_id=? AND version_no=?",
                data==null?null:write(data),source==null||source.isNull()?null:write(source),context.hasNonNull("planRef")?write(context.get("planRef")):null,
                stepId,s.id(),a.id(),a.version());
    }
    public void recordMedia(Session s,Approval approval,String mediaType,String title) {
        var a=artifact(s,approval.callId(),null,mediaType,title,"生成作品，任务编号："+approval.taskId());
        jdbc.update("UPDATE agent_artifact_version SET task_id=? WHERE session_id=? AND artifact_id=? AND version_no=?",approval.taskId(),s.id(),a.id(),a.version());
        JsonNode context=callContext(approval.callId()); attachMetadata(s,a,null,context.get("sourceRef"),context);
        Turn t=lockedTurn(approval.turnId());
        if(!"DIRECT".equals(context.path("executionMode").asText()) && t!=null && t.id().equals(s.activeTurnId())
                && t.epoch()==approval.epoch() && t.step()==approval.step() && "WAITING_TASK".equals(t.status()))
            advanceWorkspace(s,context,a);
    }
    private void advanceWorkspace(Session s,JsonNode context,AgentViews.Artifact a) {
        var w=workspace(s);
        if(!w.path("planConfirmed").asBoolean() || w.path("version").asLong()!=context.path("version").asLong()
                || !Objects.equals(w.get("planRef"),context.get("planRef")) || !Objects.equals(w.get("currentStepId"),context.get("currentStepId"))) return;
        if(w.hasNonNull("executionPlanId")) { plans.succeeded(context,a); return; }
        if(Set.of("SCRIPT","STORYBOARD","PROMPT").contains(a.type()) && a.version()>1) {
            boolean reset=false; String nextStep=null;
            for(var step:w.path("steps")) {
                ObjectNode item=(ObjectNode)step;
                if(a.id().equals(item.path("artifactRef").path("artifactId").asText())) {
                    item.set("artifactRef",reference(a)); reset=true;
                } else if(reset) { item.put("status","PENDING"); item.remove("artifactRef"); if(nextStep==null) nextStep=item.path("id").asText(); }
            }
            if(reset) { w.put("currentStepId",nextStep); saveWorkspace(s,w); return; }
        }
        String next=null; boolean completed=false;
        for(var step:w.path("steps")) {
            if(step.path("id").asText().equals(w.path("currentStepId").asText()) && step.path("kind").asText().equals(a.type())) {
                ((ObjectNode)step).put("status","COMPLETED").set("artifactRef",reference(a)); completed=true;
            } else if(next==null && !"COMPLETED".equals(step.path("status").asText())) next=step.path("id").asText();
        }
        if(completed) { w.put("currentStepId",next); saveWorkspace(s,w); }
    }
    public List<AgentViews.Message> messages(Session s) {
        var result=jdbc.query("SELECT id,seq,role,parts,create_time,client_msg_id FROM conversation_message WHERE conversation_id=? AND user_id=? ORDER BY seq DESC LIMIT 100",
                (r,n)->new AgentViews.Message(r.getString(1),r.getLong(2),r.getString(3),projectParts(read(r.getString(4))),r.getTimestamp(5).toLocalDateTime().toString(),r.getString(6)),s.conversationId(),s.userId());
        Collections.reverse(result); return result;
    }
    private JsonNode projectParts(JsonNode parts) {
        if(!parts.isArray()) return json.createArrayNode();
        for(JsonNode part:parts) {
            if(!(part instanceof ObjectNode object)) continue;
            if("choice".equals(part.path("type").asText())) {
                var i=interaction(part.path("interactionId").asText());
                if(i!=null) { object.put("status",i.status()); object.put("version",i.version()); }
            }
            if("skill_call".equals(part.path("type").asText())) {
                var c=call(part.path("skillCallId").asText());
                if(c!=null) {
                    object.put("status",c.status());
                    if("video-generation".equals(c.skillId()) && jdbc.queryForObject(
                            "SELECT COUNT(*) FROM agent_video_prompt_checkpoint p WHERE p.call_id=? "
                                    +"AND NOT EXISTS(SELECT 1 FROM agent_approval a WHERE a.call_id=p.call_id)",Integer.class,c.id())>0)
                        object.put("phase","VIDEO_PROMPT_PREPARATION");
                }
            }
        }
        return parts;
    }
    public List<Map<String,String>> history(Session s) {
        var result=jdbc.query("SELECT role,content FROM conversation_message WHERE conversation_id=? AND user_id=? AND content IS NOT NULL ORDER BY seq DESC LIMIT 12",
                (r,n)->Map.of("role",r.getString(1),"text",r.getString(2)),s.conversationId(),s.userId());
        Collections.reverse(result); return result;
    }
    public List<String> confirmedChoices(Session s) {
        var choices=jdbc.query("SELECT i.question,i.response_text FROM agent_interaction i JOIN agent_turn t ON i.turn_id=t.id WHERE t.session_id=? AND i.status='ANSWERED' ORDER BY i.created_at DESC,i.id DESC LIMIT 12",
                (r,n)->"用户已回答："+r.getString(1)+"\n"+r.getString(2),s.id());
        Collections.reverse(choices); return choices;
    }
    private static <T> T first(List<T> rows) { return rows.isEmpty()?null:rows.get(0); }
    public String write(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Agent 数据无法保存",e); }
    }
    public JsonNode read(String value) {
        try { return value==null?json.createArrayNode():json.readTree(value); } catch(Exception e) { throw new IllegalStateException("Agent 数据无法读取",e); }
    }
}
