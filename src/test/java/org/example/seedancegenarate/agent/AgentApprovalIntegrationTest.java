package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.runtime.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL/transactions, fake generation provider. Not a MySQL lock-engine proof. */
class AgentApprovalIntegrationTest {
    final ObjectMapper json=new ObjectMapper(); JdbcTemplate db; TransactionTemplate tx;
    AgentStore store; AgentApprovalStore approvals; AgentApplication app; AgentApprovalApplication approvalApp;
    AgentGenerationRuntime generation; AgentRuntime runtime; AgentGenerationGateway gateway; AsyncJobService jobs;
    AgentPlanner planner; TaskSkill skill; long conversation; TaskQuote quote; String approval;
    AtomicBoolean valid=new AtomicBoolean(true);
    org.example.seedancegenarate.config.AgentRuntimeProperties runtimeLimits;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:approval"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)"); db.update("INSERT INTO app_user VALUES(1),(2)");
        db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String file:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V40__agent_model_recovery.sql","V43__agent_turn_lifecycle.sql","V44__agent_output_repair.sql","V45__agent_scene_progress.sql","V47__agent_model_binding.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql","V54__agent_skill_output_repair.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        db.execute("CREATE TABLE job_probe(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(64),biz VARCHAR(128),payload TEXT,done BOOLEAN DEFAULT FALSE,UNIQUE(type,biz))");
        runtimeLimits=new org.example.seedancegenarate.config.AgentRuntimeProperties();runtimeLimits.setBatchEnabled(false);
        store=new AgentStore(db,json,runtimeLimits); approvals=new AgentApprovalStore(db,store);
        jobs=mock(AsyncJobService.class); gateway=mock(AgentGenerationGateway.class); planner=mock(AgentPlanner.class); skill=mock(TaskSkill.class);
        when(gateway.read(anyLong(),anyString())).thenAnswer(a->new AgentGenerationGateway.TaskView(a.getArgument(1),"PROCESSING","IMAGE",null,false,false,null));
        when(skill.descriptor()).thenReturn(new SkillDescriptor("image-generation","1","image",json.createObjectNode()));
        quote=new TaskQuote("comfyui","image-1","图像模型","IMAGE",json.createObjectNode().put("model","image-1").put("prompt","武清气象局雷达").put("ratio","16:9").put("duration",8),new BigDecimal("0.10"),"CNY");
        when(skill.quote(any(),any())).thenReturn(quote);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"image-generation",quote.inputSnapshot()));
        doAnswer(a->{
            int n=db.update("UPDATE job_probe SET done=FALSE WHERE type=? AND biz=?",a.getArgument(0,String.class),a.getArgument(1,String.class));
            if(n==0) db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0,String.class),a.getArgument(1,String.class),a.getArgument(2,String.class));
            return null;
        }).when(jobs).enqueue(anyString(),anyString(),anyString());
        when(jobs.find(anyString(),anyString())).thenAnswer(a->{var rows=db.query("SELECT * FROM job_probe WHERE type=? AND biz=?",(r,n)->{
            var j=new AsyncJob(); j.setStatus(r.getBoolean("done")?"SUCCEEDED":"READY"); return j;
        },a.getArgument(0,String.class),a.getArgument(1,String.class));return rows.isEmpty()?null:rows.get(0);});
        when(jobs.renew(any(),anyLong())).thenAnswer(a->valid.get());
        when(jobs.complete(any())).thenAnswer(a->{if(!valid.get())return false; db.update("UPDATE job_probe SET done=TRUE WHERE id=?",((AsyncJob)a.getArgument(0)).getId());return true;});
        when(jobs.failAndRetry(any(),anyString())).thenReturn(true);
        approvalApp=new AgentApprovalApplication(store,approvals,tx,jobs,gateway,json);
        generation=new AgentGenerationRuntime(store,approvals,approvalApp,gateway,jobs,tx,json);
        var runtimeModels=mock(AgentModelGateway.class);
        when(runtimeModels.channelBinding(anyString())).thenReturn("a".repeat(64));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(skill)),json,generation,mock(AgentDecisionDiagnostics.class),runtimeModels);
        app=new AgentApplication(store,tx,jobs,runtimeModels,json,approvalApp,approvals,mock(org.example.seedancegenarate.agent.application.AgentImageInputs.class));
        conversation=Long.parseLong(app.create(1,"宣传片").id());
    }
    AsyncJob next() {
        return db.query("SELECT * FROM job_probe WHERE done=FALSE ORDER BY id LIMIT 1",(r,n)->{
            var j=new AsyncJob(); j.setId(r.getLong("id")); j.setBizKey(r.getString("biz")); j.setJobType(r.getString("type")); j.setPayload(r.getString("payload")); j.setAttempts(0); return j;
        }).get(0);
    }
    void run() { var job=next(); if(AgentGenerationRuntime.JOB.equals(job.getJobType()))generation.execute(job);else runtime.execute(job,AgentRuntime.SKILL_JOB.equals(job.getJobType())); }
    void prepare() {
        app.send(1,conversation,new AgentApplication.Send("m1","生成雷达图","self")); run(); run();
        approval=db.queryForObject("SELECT id FROM agent_approval",String.class);
    }
    void approve() { approvalApp.answer(1,conversation,approval,new AgentApprovalApplication.Answer("a1",1,"APPROVE")); }
    String state() { return approvals.get(approval).status(); }
    void wake() { db.update("UPDATE agent_approval SET next_check_at=TIMESTAMPADD(SECOND,-1,NOW())"); generation.reconcile(); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    AgentGenerationGateway.TaskView success(boolean blocked) { return new AgentGenerationGateway.TaskView("tsk-1","SUCCESS","IMAGE",blocked?null:"/api/agent/media/tsk-1",blocked,false,blocked?"作品已屏蔽":null); }

    // 【测什么】删除在付费提交边界前取消审批，旧生成Worker不能调用领域提交。
    // 【怎么算红】删除未取消APPROVED或清active不生效，会调用submit或审批未取消。
    @Test void deletionBeforeSubmissionCancelsUnsubmittedApproval() throws Exception {
        prepare(); approve(); app.delete(1,conversation); run();
        assertEquals("CANCELLED",state()); verify(gateway,never()).submit(anyLong(),any(),anyString());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】删除与已SUBMITTING交错保留任务绑定和产物，重启后收尾不续跑LLM/改计划。
    // 【怎么算红】后台读取仍拒绝archived或移除current守卫，会丢绑定/重复提交/新增消息或作业。
    @Test void deletionDuringSubmissionKeepsResultWithoutContinuation() throws Exception {
        prepare(); approve(); String turn=app.snapshot(1,conversation).turn().id();
        when(gateway.submit(anyLong(),any(),anyString())).thenAnswer(a->{app.delete(1,conversation); return "tsk-1";});
        run(); assertEquals("ACCEPTED",state()); assertEquals("tsk-1",approvals.get(approval).taskId());
        long messages=count("conversation_message"), stepJobs=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Long.class);
        var stopped=store.turn(turn); generation=new AgentGenerationRuntime(store,approvals,approvalApp,gateway,jobs,tx,json);
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run(); wake();
        assertEquals("SUCCEEDED",state()); assertEquals(1,count("agent_artifact_version"));
        assertEquals("CANCELLED",store.turn(turn).status()); assertEquals(stopped.epoch(),store.turn(turn).epoch());
        assertEquals(messages,count("conversation_message")); assertEquals(stepJobs,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Long.class));
        assertEquals(0,count("agent_observation"));
        assertTrue(app.list(1).isEmpty()); verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }

    // 【测什么】报价只落确认单，文本同意/越权/过期/重复确认均不能绕开审批或多建提交作业。
    // 【怎么算红】移除busy、审批版本/owner/幂等校验，异常或job计数断言失败。
    @Test void explicitApprovalIsDurableScopedAndIdempotent() throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode)quote.inputSnapshot()).put("megapixels",1.5);
        prepare(); assertEquals("WAITING_APPROVAL",app.snapshot(1,conversation).turn().status()); verify(gateway,never()).submit(anyLong(),any(),anyString());
        assertTrue(app.snapshot(1,conversation).messages().stream().flatMap(m->java.util.stream.StreamSupport.stream(m.parts().spliterator(),false))
                .anyMatch(p->"approval".equals(p.path("type").asText())&&p.path("megapixels").doubleValue()==1.5));
        assertThrows(BusinessException.class,()->app.send(1,conversation,new AgentApplication.Send("m2","同意","self")));
        assertThrows(BusinessException.class,()->approvalApp.answer(2,conversation,approval,new AgentApprovalApplication.Answer("a1",1,"APPROVE")));
        approve(); approve(); assertEquals(3,count("job_probe"));
        assertThrows(BusinessException.class,()->approvalApp.answer(1,conversation,approval,new AgentApprovalApplication.Answer("a1",1,"REJECT")));
        assertThrows(BusinessException.class,()->approvalApp.answer(1,conversation,approval,new AgentApprovalApplication.Answer("a2",1,"APPROVE")));
    }
    // 【测什么】确认前取消/确认后提交边界前取消，不应调用生成领域接口。
    // 【怎么算红】删除APPROVED取消或begin active守卫，submit调用次数非零。
    @Test void cancellationBeforeBoundaryPreventsSubmission() throws Exception {
        prepare(); approve(); app.cancel(1,conversation,app.snapshot(1,conversation).turn().id()); run();
        assertEquals("CANCELLED",state()); verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
    // 【测什么】提交边界后取消并开启新轮，原任务绑定/结果保留但不能推进新轮。
    // 【怎么算红】把finish的current守卫改true，旧轮CANCELLED、消息/作业不增长断言必须失败。
    @Test void cancellationAfterBoundaryKeepsTaskWithoutResumingNewTurn() throws Exception {
        prepare(); approve(); String oldTurn=app.snapshot(1,conversation).turn().id();
        when(gateway.submit(anyLong(),any(),anyString())).thenAnswer(a->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals("SUBMITTING",state());
            app.cancel(1,conversation,app.snapshot(1,conversation).turn().id()); return "tsk-1";
        });
        run(); assertEquals("ACCEPTED",state()); assertEquals("CANCELLED",app.snapshot(1,conversation).turn().status());
        var newer=app.send(1,conversation,new AgentApplication.Send("m2","换个想法","self"));
        var stopped=store.turn(oldTurn); long beforeMessages=count("conversation_message");
        int beforeStepJobs=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class);
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); generation.execute(next());
        assertEquals("SUCCEEDED",state()); assertEquals(1,count("agent_artifact_version"));
        assertEquals(newer.turn().id(),app.snapshot(1,conversation).turn().id()); assertEquals("QUEUED",app.snapshot(1,conversation).turn().status());
        assertEquals("CANCELLED",store.turn(oldTurn).status());
        assertEquals(stopped.epoch(),store.turn(oldTurn).epoch()); assertEquals(stopped.step(),store.turn(oldTurn).step());
        assertEquals(beforeMessages,count("conversation_message"),"停止的旧轮不应补写助手继续消息");
        assertEquals(beforeStepJobs,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class),"停止的旧轮不能新增续跑作业");
    }
    // 【测什么】已受理但响应丢失后重建Runtime，仅查相同requestId补链；事件早到和重复不丢结果或重复推进。
    // 【怎么算红】删findAccepted快路或终态状态守卫，submit次数或产物计数超1。
    @Test void lostAcceptanceResponseAndEarlyDuplicateEventRecoverOnce() throws Exception {
        prepare(); approve(); String key=approvals.get(approval).requestId();
        when(gateway.submit(1,quote,key)).thenAnswer(a->{
            generation.onTask(new TaskStatusChangedEvent(1L,new TaskStatusChangedEvent.Message("tsk-1","SUCCESS",null,"IMAGE",null,null)));
            throw new IllegalStateException("response lost");
        });
        run(); assertEquals("SUBMITTING",state()); assertNull(approvals.get(approval).taskId());
        when(gateway.findAccepted(1,key)).thenReturn("tsk-1");
        generation=new AgentGenerationRuntime(store,approvals,approvalApp,gateway,jobs,tx,json); wake(); run();
        assertEquals("ACCEPTED",state()); verify(gateway,times(1)).submit(1,quote,key);
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); AsyncJob terminal=next(); run(); generation.execute(terminal);
        assertEquals("SUCCEEDED",state()); assertEquals(1,count("agent_artifact_version"));
        assertEquals("QUEUED",app.snapshot(1,conversation).turn().status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
    }
    // 【测什么】旧租约在领域已受理后不能绑定；新租约按相同key查回既有任务。
    // 【怎么算红】删fence与complete回滚检查后旧租约就能把taskId写入。
    @Test void leaseLossAfterAcceptanceCannotWriteBinding() throws Exception {
        prepare(); approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenAnswer(a->{valid.set(false); return "tsk-1";});
        assertThrows(IllegalStateException.class,this::run); assertNull(approvals.get(approval).taskId());
        valid.set(true); when(gateway.findAccepted(anyLong(),anyString())).thenReturn("tsk-1"); run();
        assertEquals("tsk-1",approvals.get(approval).taskId()); verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】确定未受理的涨价/余额不足终止，不当成UNKNOWN盲重试。
    // 【怎么算红】去掉GenerationRejected分类，确认单会保留SUBMITTING。
    @Test void provenRejectionFailsClearly() throws Exception {
        prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenThrow(new GenerationRejectedException("余额不足")); run();
        assertEquals("FAILED",state()); assertEquals("FAILED",app.snapshot(1,conversation).turn().status()); assertNull(approvals.get(approval).taskId());
    }
    // 【测什么】待确认过期后不能收费，账号删除在提交边界前也不能收费。
    // 【怎么算红】删expiry或userExists检查，过期审批通过或submit被调用。
    @Test void expiryAndDeletedUserCannotSubmit() throws Exception {
        prepare(); db.update("UPDATE agent_approval SET expires_at=TIMESTAMPADD(SECOND,-1,NOW())");
        assertThrows(BusinessException.class,this::approve); approvalApp.expire(); assertEquals("EXPIRED",state());
        db.update("UPDATE agent_approval SET status='PENDING',version=1,expires_at=TIMESTAMPADD(MINUTE,10,NOW())");
        db.update("UPDATE agent_turn SET status='WAITING_APPROVAL'"); approve(); db.update("DELETE FROM app_user WHERE id=1"); run();
        assertEquals("CANCELLED",state()); verify(gateway,never()).submit(anyLong(),any(),anyString());
    }
    // 【测什么】每次snapshot重新投影监管状态，已屏蔽产物无mediaPath；数据库只存task引用。
    // 【怎么算红】删除gateway.read的投影或持久化签名地址，mediaPath/数据库断言失败。
    @Test void mediaProjectionRechecksModerationWithoutPersistingUrls() throws Exception {
        prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run();
        var before=app.snapshot(1,conversation); assertTrue(before.messages().stream().flatMap(m->java.util.stream.StreamSupport.stream(m.parts().spliterator(),false)).anyMatch(p->p.path("mediaPath").asText().contains("/api/agent/media")));
        when(gateway.read(1,"tsk-1")).thenReturn(success(true)); var after=app.snapshot(1,conversation);
        assertTrue(after.messages().stream().flatMap(m->java.util.stream.StreamSupport.stream(m.parts().spliterator(),false)).filter(p->"task".equals(p.path("type").asText())).allMatch(p->p.path("blocked").asBoolean()&&p.path("mediaPath").isNull()));
        assertEquals("tsk-1",after.artifacts().get(0).taskId());
        assertFalse(db.queryForObject("SELECT content FROM agent_artifact_version",String.class).contains("http"));
    }
    // 【测什么】已采用计划每次只完成本次媒体步骤，不自动规划或提交下一付费步骤。
    // 【怎么算红】去掉Generation.finish的planConfirmed终轮条件，状态将QUEUED且新增AGENT_STEP。
    @Test void adoptedPlanMediaCompletesOneStepWithoutAutoContinuation() throws Exception {
        var s=store.owned(conversation,1,false);
        var p=store.artifact(s,"plan-fixture",null,"PLAN","计划","图片后视频");
        var w=store.workspace(s); w.put("version",1).put("planConfirmed",true).put("currentStepId","image");
        w.set("planRef",json.createObjectNode().put("artifactId",p.id()).put("version",1));
        w.putArray("steps").addObject().put("id","image").put("kind","IMAGE").put("title","图片").put("status","PENDING");
        ((com.fasterxml.jackson.databind.node.ArrayNode)w.get("steps")).addObject().put("id","video").put("kind","VIDEO").put("title","视频").put("status","PENDING");
        store.saveWorkspace(s,w);
        prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        int before=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class);
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run();
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals("video",store.workspace(s).path("currentStepId").asText());
        assertEquals("COMPLETED",store.workspace(s).path("steps").get(0).path("status").asText());
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    void durableMediaPlan() {
        durableMediaPlan(List.of(Map.of("id","image","kind","IMAGE","title","图片"),Map.of("id","video","kind","VIDEO","title","视频")));
    }
    void durableMediaPlan(List<Map<String,String>> steps) {
        var s=store.owned(conversation,1,false); var draft=store.turn(store.newTurn(s,"self","目标"));
        var call=store.call(store.newCall(draft,"plan-generation","1",json.createObjectNode()));
        var data=json.valueToTree(Map.of("goal","目标","steps",steps));
        var plan=tx.execute(t->store.recordResult(s,call,new SkillResult("PLAN","计划","图片和视频",null,data,null)));
        var w=store.workspace(s); w.put("planConfirmed",true);
        tx.executeWithoutResult(t->{store.plans().adopt(s,plan,w);store.saveWorkspace(s,w);store.status(draft.id(),"COMPLETED",null);});
        when(skill.descriptor()).thenReturn(new SkillDescriptor("image-generation","1","image",json.createObjectNode(),"IMAGE"));
    }
    // 【测什么】durable计划的单媒体成功只完成原Step，随后同Turn自动唤醒Planner，重复终态不重复产物/推进/续跑。
    // 【怎么算红】保留媒体成功暂停，或去掉approval/current/job幂等守卫，Turn、产物、Step或作业计数必须变红。
    @Test void durableMediaSuccessContinuesPlannerExactlyOnce() throws Exception {
        durableMediaPlan(); prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        int before=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class);
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); AsyncJob terminal=next(); generation.execute(terminal);
        assertEquals("SUCCEEDED",state()); assertEquals("QUEUED",app.snapshot(1,conversation).turn().status());
        assertEquals("video",app.snapshot(1,conversation).state().workspace().path("currentStepId").asText());
        assertEquals("SUCCEEDED",app.snapshot(1,conversation).state().workspace().path("steps").get(0).path("status").asText());
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
        int step=store.turn(app.snapshot(1,conversation).turn().id()).step(); generation.execute(terminal);
        assertEquals(2,count("agent_artifact_version")); assertEquals(step,store.turn(app.snapshot(1,conversation).turn().id()).step());
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        when(planner.decide(any())).thenReturn(new AgentDecision("ASK_USER","需要确认下一段视频场景",null,List.of(),null,null)); run();
        assertEquals("WAITING_USER",app.snapshot(1,conversation).turn().status()); assertEquals(1,count("agent_approval"));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】durable计划末个媒体成功由统一推进校验真实结果并完成，不再依赖尾部Planner。
    // 【怎么算红】恢复末步无条件入STEP时，COMPLETED和无待执行作业断言失败。
    @Test void durableLastMediaSuccessCompletesWithoutClosingPlanner() throws Exception {
        durableMediaPlan(List.of(Map.of("id","image","kind","IMAGE","title","图片")));
        prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run();
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】已确认未受理的单媒体计划保存拒绝观察并暂停，重放不重投、不推进或创建作品。
    // 【怎么算红】只有sceneItemId才暂停时Turn为FAILED；删除观察或重复执行保护会使状态/计数断言失败。
    @Test void durableSubmissionRejectionSuspendsWithoutResubmitting() throws Exception {
        durableMediaPlan(); prepare(); approve();
        when(gateway.submit(anyLong(),any(),anyString())).thenThrow(new GenerationRejectedException("余额不足"));
        var job=next(); generation.execute(job); generation.execute(job);
        assertEquals("FAILED",state());
        assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        assertEquals("SUSPENDED",db.queryForObject("SELECT status FROM agent_plan",String.class));
        assertEquals("MEDIA_SUBMISSION_REJECTED",db.queryForObject("SELECT code FROM agent_observation",String.class));
        assertEquals(1,count("agent_observation"));
        assertEquals(1,count("agent_artifact_version")); // Only the original plan.
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }

    // 【测什么】durable媒体失败只写安全结构化Observation并暂停，不携上游原文、不续跑或自动重投。
    // 【怎么算红】不写观察、泄漏view.message、将失败当成成功推进或创建STEP作业，任一断言必须变红。
    @Test void durableMediaFailureRecordsSafeObservationAndSuspends() throws Exception {
        durableMediaPlan(); prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        int before=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class);
        when(gateway.read(1,"tsk-1")).thenReturn(new AgentGenerationGateway.TaskView("tsk-1","FAILED","IMAGE",null,false,false,"provider-secret http://10.0.0.5"));
        wake(); AsyncJob terminal=next(); generation.execute(terminal); generation.execute(terminal);
        assertEquals("FAILED",state()); assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        assertTrue(app.snapshot(1,conversation).turn().error().contains("失败"));
        assertNotEquals("SUCCEEDED",app.snapshot(1,conversation).state().workspace().path("executionStatus").asText());
        var observation=db.queryForMap("SELECT type,code,detail FROM agent_observation");
        assertEquals("MEDIA_TASK_RESULT",observation.get("TYPE")); assertEquals("MEDIA_TASK_FAILED",observation.get("CODE"));
        assertEquals("媒体任务未成功完成；未创建作品，也未自动重投任务。",observation.get("DETAIL"));
        assertFalse(db.queryForList("SELECT detail FROM agent_observation").toString().contains("provider-secret"));
        assertFalse(db.queryForList("SELECT content,parts FROM conversation_message").toString().contains("10.0.0.5"));
        assertEquals(1,count("agent_observation")); assertEquals(1,count("agent_artifact_version"));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】媒体Task取消使用独立安全观察码，仍暂停durable计划且不产生作品或续跑。
    // 【怎么算红】把CANCELLED混成成功/普通失败，或创建Artifact/STEP作业，码值和计数断言必须变红。
    @Test void durableMediaCancellationUsesCancelledObservationCode() throws Exception {
        durableMediaPlan(); prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(new AgentGenerationGateway.TaskView("tsk-1","CANCELLED","IMAGE",null,false,false,"provider cancelled")); wake(); run();
        assertEquals("FAILED",state()); assertEquals("SUSPENDED",app.snapshot(1,conversation).turn().status());
        assertEquals("MEDIA_TASK_CANCELLED",db.queryForObject("SELECT code FROM agent_observation",String.class));
        assertEquals(1,count("agent_artifact_version"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
    }
    // 【测什么】durable任务跨提交边界后停止，旧epoch终态仅保留一份任务作品，不推进Step、写观察或续跑。
    // 【怎么算红】去掉activeTurn/epoch守卫，取消后Step会SUCCEEDED或出现Observation/新STEP作业。
    @Test void stoppedDurableTurnKeepsLateTaskArtifactWithoutPlanContinuation() throws Exception {
        durableMediaPlan(); prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        String turn=app.snapshot(1,conversation).turn().id(); int before=db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class);
        app.cancel(1,conversation,turn); when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); AsyncJob terminal=next(); generation.execute(terminal); generation.execute(terminal);
        assertEquals("SUCCEEDED",state()); assertEquals("CANCELLED",store.turn(turn).status());
        assertEquals(2,count("agent_artifact_version")); assertEquals(0,count("agent_observation"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_plan_step WHERE status='SUCCEEDED'",Integer.class));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】媒体成功的下一Planner作业入队失败时整个终态收口回滚，reconcile沿原task恢复且不再submit。
    // 【怎么算红】将Artifact/Step与STEP入队拆事务，或恢复时换requestId重投，首轮回滚与submit次数断言必须变红。
    @Test void continuationEnqueueFailureRollsBackAndReconcileUsesSameTask() throws Exception {
        durableMediaPlan(); prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); AsyncJob terminal=next();
        AtomicBoolean failStepOnce=new AtomicBoolean(true);
        doAnswer(a->{
            String type=a.getArgument(0,String.class),biz=a.getArgument(1,String.class),payload=a.getArgument(2,String.class);
            if(AgentRuntime.STEP_JOB.equals(type)&&failStepOnce.getAndSet(false))
                throw new org.springframework.dao.DataAccessResourceFailureException("step enqueue unavailable");
            int n=db.update("UPDATE job_probe SET done=FALSE WHERE type=? AND biz=?",type,biz);
            if(n==0) db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",type,biz,payload);
            return null;
        }).when(jobs).enqueue(anyString(),anyString(),anyString());

        generation.execute(terminal);
        assertEquals("ACCEPTED",state()); assertEquals(1,count("agent_artifact_version"));
        assertEquals("WAITING_TASK",db.queryForObject("SELECT status FROM agent_plan_step WHERE ordinal_no=0",String.class));
        wake(); run();
        assertEquals("SUCCEEDED",state()); assertEquals(2,count("agent_artifact_version"));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM agent_plan_step WHERE ordinal_no=0",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP' AND done=FALSE",Integer.class));
        verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】DIRECT在durable计划旁独立生成，不能抢占计划绑定或改其状态。
    // 【怎么算红】newDirectTurn无条件plans.bind，计划的turn_id将变成DIRECT轮次。
    @Test void directDoesNotBindDurablePlan() {
        durableMediaPlan(); String before=db.queryForObject("SELECT turn_id FROM agent_plan",String.class);
        prepareDirect("AUDIO"); assertEquals(before,db.queryForObject("SELECT turn_id FROM agent_plan",String.class));
        assertEquals("READY",db.queryForObject("SELECT status FROM agent_plan",String.class));
    }
    // 【测什么】任务门铃必须等领域提交后独立入队，入队故障不能回滚已完成的生成。
    // 【怎么算红】去掉afterCommit延迟或异常隔离，事务内never校验或外层提交断言失败。
    @Test void terminalEventWakeFailureCannotRollbackDomainTransaction() throws Exception {
        prepare(); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        clearInvocations(jobs);
        doThrow(new IllegalStateException("queue unavailable")).when(jobs).enqueue(eq(AgentGenerationRuntime.JOB),eq(approval),eq(approval));
        assertDoesNotThrow(()->tx.executeWithoutResult(t->{
            db.update("INSERT INTO app_user VALUES(3)");
            generation.onTask(new TaskStatusChangedEvent(1L,new TaskStatusChangedEvent.Message("tsk-1","SUCCESS",null,"IMAGE",null,null)));
            verify(jobs,never()).enqueue(anyString(),anyString(),anyString());
        }));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=3",Integer.class));
        verify(jobs,times(1)).enqueue(AgentGenerationRuntime.JOB,approval,approval);
    }

    void prepareDirect(String mediaType) {
        prepareDirect(mediaType,null);
    }
    void prepareDirect(String mediaType,com.fasterxml.jackson.databind.JsonNode source) {
        tx.executeWithoutResult(status->{
            var s=store.owned(conversation,1,true);
            var t=store.turn(store.newDirectTurn(s,"直接生成"));
            var callId=store.newDirectCall(t,json.createObjectNode(),source);
            quote=new TaskQuote("provider","model","直接模型",mediaType,json.createObjectNode().put("model","model").put("prompt","雷达").put("duration",180),new BigDecimal("0.10"),"CNY","DIRECT");
            generation.awaitApproval(s,t,store.call(callId),quote);
            store.touch(s);
        });
        approval=db.queryForObject("SELECT id FROM agent_approval",String.class);
    }
    // 【测什么】音乐直接生成仅经显式费用确认，终态生成音频作品、不调用Planner或排入续轮。
    // 【怎么算红】拒绝AUDIO或删DIRECT完成守卫，awaitApproval失败或终轮状态/STEP计数失败。
    @Test void directAudioFinishesWithoutLlmContinuation() throws Exception {
        prepareDirect("AUDIO"); verify(gateway,never()).submit(anyLong(),any(),anyString());
        approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(new AgentGenerationGateway.TaskView("tsk-1","SUCCESS","AUDIO","/api/agent/media/tsk-1",false,false,null));
        wake(); run();
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals("AUDIO",app.snapshot(1,conversation).artifacts().get(0).type());
        assertEquals("tsk-1",app.snapshot(1,conversation).artifacts().get(0).taskId());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        verifyNoInteractions(planner); verify(gateway,times(1)).submit(anyLong(),any(),anyString());
    }
    // 【测什么】图片直接模式同样必须结束当前轮，不能因没有采用计划而触发LLM。
    // 【怎么算红】移除DIRECT终轮分支，状态会QUEUED并新增AGENT_STEP。
    @Test void directImageFinishesWithoutPlanAndLlmContinuation() throws Exception {
        prepareDirect("IMAGE"); approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run();
        assertEquals("COMPLETED",app.snapshot(1,conversation).turn().status());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE type='AGENT_STEP'",Integer.class));
        verifyNoInteractions(planner);
    }
    // 【测什么】手动生成冻结明确来源，但不冒认已采用计划的一步；旧selection不会成为隐式来源。
    // 【怎么算红】newDirectCall不清plan/step/selection或丢sourceRef，context/作品归属/计划未变化断言失败。
    @Test void directGenerationPreservesSourceWithoutAdvancingAdoptedPlan() throws Exception {
        var s=store.owned(conversation,1,false); var p=store.artifact(s,"fixture",null,"PLAN","计划","生成图片");
        var source=store.artifact(s,"source",null,"SCRIPT","脚本","雷达");
        var ref=json.createObjectNode().put("artifactId",source.id()).put("version",source.version());
        var w=store.workspace(s); w.put("version",3).put("planConfirmed",true).put("currentStepId","image");
        w.set("planRef",json.createObjectNode().put("artifactId",p.id()).put("version",1)); w.set("selection",ref);
        w.putArray("steps").addObject().put("id","image").put("kind","IMAGE").put("status","PENDING"); store.saveWorkspace(s,w);
        prepareDirect("IMAGE",ref);
        var context=store.callContext(approvals.get(approval).callId());
        assertEquals(ref,context.path("sourceRef")); assertTrue(context.path("planRef").isNull());
        assertTrue(context.path("currentStepId").isNull()); assertTrue(context.path("steps").isEmpty());
        assertEquals("DIRECT",app.snapshot(1,conversation).turn().mode()); assertNull(app.snapshot(1,conversation).turn().channel());
        approve(); when(gateway.submit(anyLong(),any(),anyString())).thenReturn("tsk-1"); run();
        when(gateway.read(1,"tsk-1")).thenReturn(success(false)); wake(); run();
        var result=app.snapshot(1,conversation).artifacts().stream().filter(a->"tsk-1".equals(a.taskId())).findFirst().orElseThrow();
        assertEquals(ref,result.sourceRef()); assertNull(result.planRef()); assertNull(result.stepId()); assertEquals(w,store.workspace(s));
        var another=store.turn(store.newDirectTurn(s,"无引用")); var call=store.newDirectCall(another,json.createObjectNode(),null);
        assertTrue(store.callContext(call).path("sourceRef").isNull()); assertTrue(store.callContext(call).path("selection").isNull());
    }
    // 【测什么】普通LLM Skill不能伪造DIRECT报价扩权到参考素材或音频。
    // 【怎么算红】删origin与持久化call执行模式的一致性校验，伪造报价不会抛异常并落审批。
    @Test void ordinarySkillCannotEscalateToDirectQuote() {
        var s=store.owned(conversation,1,false); var t=store.turn(store.newTurn(s,"self","雷达"));
        var call=store.call(store.newCall(t,"image-generation","1",json.createObjectNode()));
        var forged=new TaskQuote("provider","model","model","IMAGE",json.createObjectNode(),BigDecimal.ONE,"CNY","DIRECT");
        assertThrows(IllegalArgumentException.class,()->generation.awaitApproval(s,t,call,forged));
        assertEquals(0,count("agent_approval"));
    }
}
