package org.example.seedancegenarate.agent.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.api.AgentViews;
import org.example.seedancegenarate.agent.runtime.AgentBatchRuntime;
import org.example.seedancegenarate.config.AgentRuntimeProperties;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;

/** Parent authorization and barrier; caller holds the Session lock and owns its transaction. */
@lombok.extern.slf4j.Slf4j
public final class AgentBatchStore {
    private final JdbcTemplate db;private final ObjectMapper json;private final AgentRuntimeProperties limits;
    AgentBatchStore(JdbcTemplate db,ObjectMapper json,AgentRuntimeProperties limits){this.db=db;this.json=json;this.limits=limits;}
    public boolean enabled(){return limits.isBatchEnabled();}
    public record Batch(String id,String session,String turn,long epoch,int step,String parent,String plan,String planStep,long workspace,
                        String status,int version,String binding,String quotes,LocalDateTime expires){}
    public Batch get(String id) {
        var rows=db.query("SELECT * FROM agent_generation_batch WHERE id=?",(r,n)->new Batch(r.getString("id"),r.getString("session_id"),r.getString("turn_id"),r.getLong("epoch"),r.getInt("step_no"),r.getString("parent_call_id"),r.getString("plan_id"),r.getString("plan_step_id"),r.getLong("workspace_version"),r.getString("status"),r.getInt("version"),r.getString("binding_hash"),r.getString("quote_set_hash"),r.getTimestamp("expires_at").toLocalDateTime()),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public String grant(String approval){var ids=db.query("SELECT grant_id FROM agent_approval WHERE id=?",(r,n)->r.getString(1),approval);return ids.isEmpty()?null:ids.get(0);}
    private String id(){return UUID.randomUUID().toString();}
    private String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical(json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    /** MySQL JSON reorders object keys; authorization hashes must not depend on storage serialization. */
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()){var out=json.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(node.get(k))));return out;}
        if(node.isArray()){var out=json.createArrayNode();node.forEach(n->out.add(canonical(n)));return out;}
        if(node.isNumber())return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        return node;
    }
    private JsonNode read(String value){try{return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public String create(AgentStore store,Session s,Turn t,Call parent,AgentBatchRuntime.Prepared prepared) {
        ObjectNode context=(ObjectNode)store.callContext(parent.id());
        if(!store.workspaceMatches(s,parent)||prepared.items().isEmpty()||prepared.items().size()>12)throw BusinessException.conflict("批次准备期间创作状态已改变");
        String plan=context.path("executionPlanId").asText();
        if(db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE id=? AND plan_id=? AND scope='STORYBOARD_SCENES'",Long.class,prepared.stepId(),plan)!=1)throw BusinessException.conflict("当前媒体组已改变");
        var expected=db.query("SELECT id FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED' ORDER BY ordinal_no",(r,n)->r.getString(1),prepared.stepId());
        if(!expected.equals(prepared.items().stream().map(AgentBatchRuntime.Item::sceneId).toList()))throw BusinessException.conflict("批次幕集合已改变");
        BigDecimal total=prepared.items().stream().map(i->i.quote().amount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        money(total);prepared.items().forEach(i->money(i.quote().amount()));
        String id=id(),quotes=hash(store.write(prepared.items())),binding=hash(store.write(List.of(s.id(),plan,prepared.stepId(),t.id(),t.epoch(),t.step(),context.path("version").asLong(),parent.id(),quotes)));
        var first=prepared.items().get(0).quote();
        db.update("INSERT INTO agent_generation_batch(id,session_id,turn_id,epoch,step_no,parent_call_id,plan_id,plan_step_id,workspace_version,status,binding_hash,quote_set_hash,output_type,total_amount,currency,parallel_limit,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,TIMESTAMPADD(MINUTE,10,NOW()))",
                id,s.id(),t.id(),t.epoch(),t.step(),parent.id(),plan,prepared.stepId(),context.path("version").asLong(),binding,quotes,first.mediaType(),total,first.currency(),limits.getBatchParallelLimit());
        int slot=0;
        for(var item:prepared.items()) {
            String call=id(),approval=id();ObjectNode frozen=context.deepCopy();
            frozen.put("batchId",id).put("sceneItemId",item.sceneId());frozen.set("sourceRef",item.source());frozen.set("selection",item.source());
            ObjectNode input=item.quote().inputSnapshot().deepCopy();input.set("source",item.source());
            db.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no,context_json,call_slot) VALUES(?,?,?,?,?,'WAITING_APPROVAL',?,?,?,?)",
                    call,t.id(),parent.skillId(),parent.skillVersion(),input.toString(),t.epoch(),t.step(),frozen.toString(),++slot);
            db.update("INSERT INTO agent_approval(id,session_id,turn_id,call_id,epoch,step_no,status,quote_json,request_id,grant_id,expires_at) VALUES(?,?,?,?,?,?,'BATCH_PENDING',?,?,?,TIMESTAMPADD(MINUTE,10,NOW()))",
                    approval,s.id(),t.id(),call,t.epoch(),t.step(),store.write(item.quote()),"agent:"+approval,id);
            db.update("INSERT INTO agent_batch_item(id,batch_id,scene_id,call_id,approval_id,ordinal_no,source_ref) VALUES(?,?,?,?,?,?,?)",id(),id,item.sceneId(),call,approval,item.ordinal(),item.source().toString());
            db.update("UPDATE agent_plan_scene SET skill_call_id=?,status='WAITING_APPROVAL' WHERE id=?",call,item.sceneId());
        }
        store.callStatus(parent.id(),"WAITING_APPROVAL",null);store.executionStatus(t.id(),"WAITING_APPROVAL",null);
        var parts=json.createArrayNode();parts.addObject().put("type","batch_approval").put("grantId",id).put("batchId",id);
        store.message(s,t.id(),"ASSISTANT","请审阅整组具体画面与统一生成规格，确认后按总额上限逐项生成。",parts,null,null);
        return id;
    }
    public boolean current(AgentStore store,Session s,Batch b) {
        if(b==null||!b.session().equals(s.id())||!b.turn().equals(s.activeTurnId()))return false;
        Turn t=store.lockedTurn(b.turn());
        return t!=null&&t.epoch()==b.epoch()&&t.step()==b.step()&&Set.of("WAITING_APPROVAL","SUBMITTING","WAITING_TASK").contains(t.status())
                &&store.workspace(s).path("version").asLong()==b.workspace()
                &&db.queryForObject("SELECT COUNT(*) FROM agent_plan WHERE id=? AND session_id=? AND turn_id=? AND execution_epoch=? AND status<>'CANCELLED'",Long.class,b.plan(),s.id(),t.id(),t.epoch())==1
                &&db.queryForObject("SELECT COUNT(*) FROM conversation WHERE id=? AND user_id=? AND archived=0",Long.class,s.conversationId(),s.userId())==1;
    }
    public boolean authorized(AgentStore store,Session s,String approval) {
        String grant=grant(approval);if(grant==null)return false;Batch b=get(grant);
        return b!=null&&"RUNNING".equals(b.status())&&current(store,s,b)
                &&db.queryForObject("SELECT COUNT(*) FROM agent_batch_item WHERE batch_id=? AND approval_id=?",Long.class,b.id(),approval)==1;
    }
    public void approve(AgentStore store,Session s,Batch b,boolean accepted) {
        db.update("UPDATE agent_generation_batch SET status=?,version=version+1,updated_at=NOW() WHERE id=?",accepted?"RUNNING":"REJECTED",b.id());
        db.update("UPDATE agent_approval SET status=? WHERE grant_id=? AND status='BATCH_PENDING'",accepted?"BATCH_QUEUED":"CANCELLED",b.id());
        db.update("UPDATE agent_skill_call SET status=? WHERE id IN (SELECT call_id FROM agent_batch_item WHERE batch_id=?)",accepted?"BATCH_QUEUED":"CANCELLED",b.id());
        store.callStatus(b.parent(),accepted?"WAITING_TASK":"CANCELLED",null);
        store.executionStatus(b.turn(),accepted?"WAITING_TASK":"CANCELLED",null);
        if(!accepted)settleUnsubmitted(b.id());
    }
    public List<String> dispatch(AgentStore store,Session s,String id) {
        Batch b=get(id);if(b==null||!"RUNNING".equals(b.status()))return List.of();
        db.update("UPDATE agent_generation_batch SET updated_at=NOW() WHERE id=?",id);
        if(!current(store,s,b)){cancel(b.turn());return List.of();}
        int limit=db.queryForObject("SELECT parallel_limit FROM agent_generation_batch WHERE id=?",Integer.class,id);
        int occupied=db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE grant_id=? AND status IN ('APPROVED','SUBMITTING','ACCEPTED')",Integer.class,id);
        var ready=db.query("SELECT a.id FROM agent_approval a JOIN agent_batch_item i ON i.approval_id=a.id WHERE a.grant_id=? AND a.status='BATCH_QUEUED' ORDER BY i.ordinal_no LIMIT ?",(r,n)->r.getString(1),id,Math.max(0,limit-occupied));
        for(String approval:ready) {
            db.update("UPDATE agent_approval SET status='APPROVED',updated_at=NOW() WHERE id=? AND status='BATCH_QUEUED'",approval);
            db.update("UPDATE agent_skill_call SET status='APPROVED' WHERE id=(SELECT call_id FROM agent_approval WHERE id=?)",approval);
        }
        return ready;
    }
    /** Returns a single continuation only on the first all-success settlement. */
    public Optional<Turn> settled(AgentStore store,Session s,String approval,boolean success) {
        Batch b=get(grant(approval));if(b==null||!"RUNNING".equals(b.status())||!current(store,s,b))return Optional.empty();
        if(!success) {
            var call=db.queryForObject("SELECT call_id FROM agent_approval WHERE id=?",String.class,approval);
            store.plans().sceneFailed(store.callContext(call),"FAILED");
        }
        int pending=db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE grant_id=? AND status IN ('BATCH_PENDING','BATCH_QUEUED','APPROVED','SUBMITTING','ACCEPTED')",Integer.class,b.id());
        if(pending!=0)return Optional.empty();
        int failures=db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE grant_id=? AND status<>'SUCCEEDED'",Integer.class,b.id());
        failures+=db.queryForObject("SELECT COUNT(*) FROM agent_plan_scene WHERE plan_step_id=? AND status<>'SUCCEEDED'",Integer.class,b.planStep());
        String state=failures==0?"SUCCEEDED":"PARTIAL_FAILED";
        if(db.update("UPDATE agent_generation_batch SET status=?,updated_at=NOW() WHERE id=? AND status='RUNNING'",state,b.id())!=1)return Optional.empty();
        store.callStatus(b.parent(),failures==0?"SUCCEEDED":"FAILED",failures==0?null:"部分分镜未完成，成功作品已保存");
        if(failures>0){store.executionStatus(b.turn(),"SUSPENDED","部分分镜未完成，成功作品已保留。请明确重新启动未完成幕并重新确认费用。");return Optional.empty();}
        return store.advance(s,store.turn(b.turn()));
    }
    public void cancel(String turn) {
        db.update("UPDATE agent_generation_batch SET status='CANCELLED',version=version+1,updated_at=NOW() WHERE turn_id=? AND status IN ('PENDING','APPROVED','RUNNING')",turn);
        db.update("UPDATE agent_approval SET status='CANCELLED',version=version+1 WHERE turn_id=? AND grant_id IS NOT NULL AND status IN ('BATCH_PENDING','BATCH_QUEUED','APPROVED')",turn);
        db.update("UPDATE agent_skill_call SET status='CANCELLED' WHERE turn_id=? AND status='BATCH_QUEUED'",turn);
        db.update("UPDATE agent_skill_call SET status='CANCELLED' WHERE id IN (SELECT parent_call_id FROM agent_generation_batch WHERE turn_id=? AND status='CANCELLED')",turn);
        for(String grant:db.query("SELECT id FROM agent_generation_batch WHERE turn_id=? AND status='CANCELLED'",(r,n)->r.getString(1),turn))settleUnsubmitted(grant);
    }
    /** Exact child ownership only: an unknown submission must never be reclassified as unsubmitted. */
    private void settleUnsubmitted(String grant) {
        db.update("UPDATE agent_approval SET status='CANCELLED',version=version+1,updated_at=NOW() WHERE grant_id=? AND task_id IS NULL AND status IN ('BATCH_PENDING','BATCH_QUEUED','PENDING','APPROVED')",grant);
        var children=db.query("SELECT i.call_id,i.scene_id FROM agent_batch_item i JOIN agent_approval a ON a.id=i.approval_id WHERE i.batch_id=? AND a.grant_id=? AND a.task_id IS NULL AND a.status IN ('CANCELLED','REJECTED','EXPIRED','STALE')",(r,n)->new String[]{r.getString(1),r.getString(2)},grant,grant);
        for(var child:children) {
            db.update("UPDATE agent_skill_call SET status='CANCELLED' WHERE id=? AND status NOT IN ('SUCCEEDED','SUBMITTING','WAITING_TASK','CANCELLED')",child[0]);
            db.update("UPDATE agent_plan_scene SET status='CANCELLED',updated_at=NOW() WHERE id=? AND skill_call_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",child[1],child[0]);
        }
    }
    /** Called before LLM work and human-resume budgets. Terminal authorizations are never reused. */
    public boolean beforeStep(AgentStore store,Session s,Turn t) {
        if(t==null||!t.id().equals(s.activeTurnId()))return true;
        var w=store.workspace(s);
        if(!w.hasNonNull("executionPlanId")||!w.hasNonNull("currentStepId"))return true;
        var grants=db.query("SELECT b.id FROM agent_generation_batch b JOIN agent_plan_step p ON p.id=b.plan_step_id JOIN agent_turn t ON t.id=b.turn_id WHERE b.session_id=? AND b.plan_id=? AND p.step_key=? ORDER BY t.turn_seq DESC,b.epoch DESC,b.step_no DESC,b.id DESC LIMIT 1",(r,n)->r.getString(1),s.id(),w.path("executionPlanId").asText(),w.path("currentStepId").asText());
        if(grants.isEmpty())return true;
        if(!Set.of("CANCELLED","REJECTED","EXPIRED","STALE","PARTIAL_FAILED").contains(get(grants.get(0)).status()))return true;
        String grant=grants.get(0);settleUnsubmitted(grant);
        boolean waiting=db.queryForObject("SELECT COUNT(*) FROM agent_approval WHERE grant_id=? AND status IN ('SUBMITTING','ACCEPTED')",Integer.class,grant)>0;
        String reason=waiting?"这组生成已停止追加任务，已提交的任务仍在核实或生成。请等待它们完成，再到分镜作品卡选择逐幕生成视频或参考图，重新准备未完成幕并确认新费用。":"这组生成已取消、过期或未全部完成，已有作品已保留。请到分镜作品卡选择逐幕生成视频或参考图，重新准备未完成幕，审阅新报价并确认费用；普通继续不会重新购买。";
        if(!"SUSPENDED".equals(t.status())||!reason.equals(t.error())) {
            log.info("Agent batch requires user action: session={}, turn={}, plan={}, grant={}, code={}",s.id(),t.id(),w.path("executionPlanId").asText(),grant,waiting?"MEDIA_BATCH_TASKS_SETTLING":"MEDIA_BATCH_RESTART_REQUIRED");
            var parts=json.createArrayNode();parts.addObject().put("type","text").put("text",reason);
            store.message(s,t.id(),"ASSISTANT",reason,parts,null,null);
        }
        store.executionStatus(t.id(),"SUSPENDED",reason);store.touch(s);
        return false;
    }
    public List<String> due(){return db.query("SELECT id FROM agent_generation_batch WHERE status='RUNNING' OR (status='PENDING' AND expires_at<=NOW()) ORDER BY updated_at,id LIMIT 50",(r,n)->r.getString(1));}
    public void expire(AgentStore store,Session s,Batch b) {
        if(!"PENDING".equals(b.status())||b.expires().isAfter(store.now()))return;
        cancel(b.turn());db.update("UPDATE agent_generation_batch SET status='EXPIRED',updated_at=NOW() WHERE id=?",b.id());
        if(current(store,s,b))store.executionStatus(b.turn(),"SUSPENDED","整组费用确认已过期，请重新准备并确认。");
    }
    public boolean intact(AgentStore store,Batch b) {
        var items=db.query("SELECT i.scene_id,i.ordinal_no,i.source_ref,a.quote_json FROM agent_batch_item i JOIN agent_approval a ON a.id=i.approval_id WHERE i.batch_id=? ORDER BY i.ordinal_no",(r,n)->{
            try{return new AgentBatchRuntime.Item(r.getString(1),r.getInt(2),read(r.getString(3)),json.readValue(r.getString(4),org.example.seedancegenarate.agent.skill.TaskQuote.class));}
            catch(Exception e){throw new IllegalStateException(e);}
        },b.id());
        BigDecimal total=items.stream().map(i->i.quote().amount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal frozen=db.queryForObject("SELECT total_amount FROM agent_generation_batch WHERE id=?",BigDecimal.class,b.id());
        return total.compareTo(frozen)==0&&!items.isEmpty()&&items.size()<=12&&hash(store.write(items)).equals(b.quotes())
                &&hash(store.write(List.of(b.session(),b.plan(),b.planStep(),b.turn(),b.epoch(),b.step(),b.workspace(),b.parent(),b.quotes()))).equals(b.binding());
    }
    private void money(BigDecimal amount) {
        if(amount==null||amount.signum()<0||amount.stripTrailingZeros().scale()>6||amount.precision()-amount.scale()>14)
            throw BusinessException.badRequest("批次金额超出精确计价范围");
    }
    public void project(AgentStore store,Session s,List<AgentViews.Message> messages) {
        for(var message:messages)for(var part:message.parts()) {
            if(!(part instanceof ObjectNode p)||!"batch_approval".equals(p.path("type").asText()))continue;
            Batch b=get(p.path("grantId").asText());
            if(b==null||!s.id().equals(b.session())){p.removeAll();p.put("type","text").put("text","批次不可用");continue;}
            p.put("batchId",b.id()).put("grantId",b.id()).put("version",b.version()).put("status",b.status()).put("bindingHash",b.binding()).put("quoteSetHash",b.quotes()).put("expiresAt",b.expires().toString());
            boolean requote=store.batchRequotes().eligible(store,s,b);
            p.put("canRequote",requote).put("requoteReason",requote?"可申请重新核价；规格与提示词绑定复验通过后，仍需确认新费用。":"如需继续，请到分镜作品卡重新准备并确认新费用。");
            db.query("SELECT total_amount,currency,output_type,parallel_limit FROM agent_generation_batch WHERE id=?",r->{p.put("maxTotalAmount",r.getBigDecimal(1).toPlainString()).put("currency",r.getString(2)).put("outputType",r.getString(3)).put("parallelLimit",r.getInt(4));},b.id());
            var items=p.putArray("items");int[] counts=new int[4];
            db.query("SELECT i.id,i.source_ref,a.quote_json,a.status,a.task_id FROM agent_batch_item i JOIN agent_approval a ON a.id=i.approval_id WHERE i.batch_id=? ORDER BY i.ordinal_no",r->{
                JsonNode quote=read(r.getString(3)),input=quote.path("inputSnapshot");String state=r.getString(4),task=r.getString(5);
                String publicStatus="BATCH_PENDING".equals(state)?"PENDING":"BATCH_QUEUED".equals(state)?"QUEUED":state;
                var item=items.addObject().put("itemKey",r.getString(1)).put("modelLabel",quote.path("modelLabel").asText()).put("prompt",input.path("prompt").asText()).put("ratio",input.path("ratio").asText()).put("duration",input.path("duration").asInt()).put("amount",quote.path("amount").asText()).put("status",publicStatus).put("taskId",task);
                item.set("sourceRef",read(r.getString(2)));if(input.hasNonNull("megapixels"))item.set("megapixels",input.get("megapixels"));
                org.example.seedancegenarate.agent.generation.AgentReferenceProjection.apply(item,input);
                if("SUCCEEDED".equals(state))counts[0]++;else if(Set.of("FAILED","CANCELLED","REJECTED","EXPIRED").contains(state))counts[1]++;else if(Set.of("APPROVED","SUBMITTING","ACCEPTED").contains(state))counts[2]++;else counts[3]++;
                if(task!=null)db.query("SELECT artifact_id,version_no FROM agent_artifact_version WHERE session_id=? AND user_id=? AND task_id=?",a->{item.set("artifactRef",json.createObjectNode().put("artifactId",a.getString(1)).put("version",a.getInt(2)));},s.id(),s.userId(),task);
            },b.id());
            p.putObject("summary").put("total",items.size()).put("succeeded",counts[0]).put("failed",counts[1]).put("running",counts[2]).put("queued",counts[3]);
        }
    }
}
