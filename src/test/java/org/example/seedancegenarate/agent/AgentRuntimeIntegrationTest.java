package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.AgentApplication;
import org.example.seedancegenarate.agent.model.*;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.agent.runtime.AgentRuntime;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.application.AgentApprovalApplication;
import org.example.seedancegenarate.agent.persistence.AgentApprovalStore;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.config.AgentRuntimeProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AsyncJobService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactions/SQL with H2 MySQL mode; NOT proof of MySQL lock semantics or production recovery. */
class AgentRuntimeIntegrationTest {
    JdbcTemplate db; AgentStore store; AgentApplication app; AgentRuntime runtime; TransactionTemplate tx;
    AsyncJobService jobs; AgentPlanner planner; CreativeSkill skill; SkillRegistry skills; AgentModelGateway models;
    AgentDecisionDiagnostics diagnostics;
    final ObjectMapper json=new ObjectMapper(); final AtomicBoolean leaseValid=new AtomicBoolean(true);
    long id;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:agent"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)"); db.update("INSERT INTO app_user VALUES(1),(2)");
        db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String migration:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V40__agent_model_recovery.sql","V43__agent_turn_lifecycle.sql","V44__agent_output_repair.sql","V45__agent_scene_progress.sql","V47__agent_model_binding.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql","V54__agent_skill_output_repair.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+migration)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V53__agent_video_prompt_repair.sql")).execute(ds);
        db.execute("CREATE TABLE job_probe(id BIGINT AUTO_INCREMENT PRIMARY KEY,type VARCHAR(64),biz VARCHAR(128),payload TEXT,done BOOLEAN DEFAULT FALSE,UNIQUE(type,biz))");
        store=new AgentStore(db,json); jobs=mock(AsyncJobService.class); planner=mock(AgentPlanner.class);
        skill=mock(CreativeSkill.class); when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode()));
        skills=new SkillRegistry(List.of(skill));
        doAnswer(a->{db.update("INSERT INTO job_probe(type,biz,payload) VALUES(?,?,?)",a.getArgument(0),a.getArgument(1),a.getArgument(2));return null;})
                .when(jobs).enqueue(anyString(),anyString(),anyString());
        when(jobs.renew(any(),anyLong())).thenAnswer(a->leaseValid.get());
        when(jobs.complete(any())).thenAnswer(a->{if(!leaseValid.get())return false; db.update("UPDATE job_probe SET done=TRUE WHERE id=?",((AsyncJob)a.getArgument(0)).getId());return true;});
        when(jobs.failAndRetry(any(),anyString())).thenReturn(true);
        models=mock(AgentModelGateway.class);
        when(models.channelBinding(anyString())).thenReturn("a".repeat(64));
        when(models.defaultChannel()).thenReturn("self-hosted");
        app=new AgentApplication(store,tx,jobs,models,json,mock(AgentApprovalApplication.class),new AgentApprovalStore(db,store),mock(org.example.seedancegenarate.agent.application.AgentImageInputs.class));
        diagnostics=mock(AgentDecisionDiagnostics.class);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        id=Long.parseLong(app.create(1,"武清宣传片").id());
    }
    AgentApplication.Send send(String key,String text) { return new AgentApplication.Send(key,text,"self-hosted"); }
    AsyncJob next() {
        return db.query("SELECT * FROM job_probe WHERE done=FALSE ORDER BY id LIMIT 1",(r,n)->{
            var j=new AsyncJob(); j.setId(r.getLong("id")); j.setBizKey(r.getString("biz")); j.setJobType(r.getString("type")); j.setPayload(r.getString("payload")); j.setAttempts(0); return j;
        }).get(0);
    }
    void run() { var job=next(); runtime.execute(job,AgentRuntime.SKILL_JOB.equals(job.getJobType())); }
    AgentDecision ask() { return new AgentDecision("ASK_USER","选择风格","宣传片目标",List.of(new AgentDecision.Option("a","科技风"),new AgentDecision.Option("b","城市风")),null,null); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }

    // 【测什么】自动切片继承已绑定的模型身份，不能在后台换模型后静默采用新模型。
    // 【怎么算红】yieldToSystemContinue不复制model_binding时，新Turn绑定为null，断言失败。
    @Test void systemContinuePreservesModelBinding() {
        app.send(1,id,send("binding-yield","继续创作"));
        var session=store.owned(id,1,false);var turn=store.turn(session.activeTurnId());
        db.update("UPDATE agent_turn SET model_binding=? WHERE id=?","a".repeat(64),turn.id());
        var child=tx.execute(t->store.yieldToSystemContinue(store.owned(id,1,true),turn)).orElseThrow();
        assertEquals("a".repeat(64),db.queryForObject("SELECT model_binding FROM agent_turn WHERE id=?",String.class,child.id()));
    }
    // 【测什么】消息受理后模型身份变化不执行Planner、不切备用模型，恢复原配置能继续原Turn。
    // 【怎么算红】Runtime忽略已保存绑定或覆盖绑定，会调用Planner且不会进入SUSPENDED。
    @Test void changedModelSuspendsWithoutPlannerAndOriginalCanResume() {
        var sent=app.send(1,id,send("bind-start","制作脚本"));
        assertEquals("a".repeat(64),db.queryForObject("SELECT model_binding FROM agent_turn WHERE id=?",String.class,sent.turn().id()));
        when(models.channelBinding(anyString())).thenReturn("b".repeat(64));run();
        var stopped=app.snapshot(1,id);assertEquals("SUSPENDED",stopped.turn().status());
        assertTrue(stopped.turn().error().contains("MODEL_CONFIGURATION_CHANGED"));
        verifyNoInteractions(planner);assertEquals(0,count("agent_skill_call"));
        when(models.channelBinding(anyString())).thenReturn("a".repeat(64));
        when(planner.decide(any())).thenReturn(ask());
        app.send(1,id,send("bind-resume","继续"));run();
        assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        assertEquals(sent.turn().id(),app.snapshot(1,id).turn().id());
    }

    // 【测什么】决策切片、Plan步数和人工恢复是三个独立配置，默认分别为16/100/8且拒绝非正数。
    // 【怎么算红】复用一个MAX_STEPS、留下错误默认或setter接受0/负数，这条必须变红。
    @Test void runtimeLimitsAreIndependentValidatedProperties() {
        var limits=new AgentRuntimeProperties();
        assertEquals(16,limits.getMaxDecisionsPerTurn()); assertEquals(100,limits.getMaxPlanSteps()); assertEquals(8,limits.getMaxHumanResumes());
        assertThrows(IllegalArgumentException.class,()->limits.setMaxDecisionsPerTurn(0));
        assertThrows(IllegalArgumentException.class,()->limits.setMaxPlanSteps(-1));
        assertThrows(IllegalArgumentException.class,()->limits.setMaxHumanResumes(0));
    }

    // 【测什么】普通用户Turn持久化USER_MESSAGE与非空单调turn_seq。
    // 【怎么算红】newTurn仍仅依赖迁移默认而不分配序号，turn_seq为null时这条必须变红。
    @Test void userTurnsHaveDurableTriggerAndSequence() {
        var first=app.send(1,id,send("first","first"));
        assertEquals("USER_MESSAGE",db.queryForObject("SELECT trigger_type FROM agent_turn WHERE id=?",String.class,first.turn().id()));
        assertEquals(1L,db.queryForObject("SELECT turn_seq FROM agent_turn WHERE id=?",Long.class,first.turn().id()));
        db.update("UPDATE agent_turn SET status='COMPLETED' WHERE id=?",first.turn().id());
        var second=app.send(1,id,send("second","second"));
        assertEquals(2L,db.queryForObject("SELECT turn_seq FROM agent_turn WHERE id=?",Long.class,second.turn().id()));
    }

    // 【测什么】PlanGenerationSkill与Runtime能保存字段各自合法但聚合后超过旧16000/20000/24000字符的100步Plan。
    // 【怎么算红】仅修改步数而保留任一旧PLAN聚合上限，作品不会落库且状态不是COMPLETED。
    @Test void legalHundredStepPlanIsNotRejectedByOldAggregateLimits() {
        var output=json.createObjectNode().put("title","长计划").put("goal","目".repeat(4000));
        var constraints=output.putArray("constraints"); for(int i=0;i<12;i++)constraints.add("约".repeat(500));
        var steps=output.putArray("steps");
        for(int i=0;i<100;i++)steps.addObject().put("id",String.format("step-%027d",i)).put("kind","SCRIPT").put("title","步".repeat(120));
        assertTrue(output.toString().length()>24000);
        var gateway=mock(AgentModelGateway.class);
        when(gateway.complete(any(),eq("AGENT_CREATIVE_PLAN"),anyString(),anyString())).thenReturn(output.toString());
        skill=new PlanGenerationSkill(gateway,json); skills=new SkillRegistry(List.of(skill));
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"plan-generation",json.createObjectNode().put("instruction","生成长计划")));
        app.send(1,id,send("long-plan","生成一份详细长计划")); run(); run();
        var plan=app.snapshot(1,id).artifacts().stream().filter(a->"PLAN".equals(a.type())).findFirst().orElseThrow();
        assertEquals(100,plan.data().path("steps").size()); assertTrue(plan.content().length()>16000);
        assertTrue(plan.data().toString().length()>20000); assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
    }

    // 【测什么】默认标题来自首条需求并持久化，快照、侧栏与重放一致，后续对话不改名。
    // 【怎么算红】遗漏AUTO标记或首条标题更新，仍为新的创作；每轮改名会使最后断言失败。
    @Test void defaultTitleFollowsFirstMessageAndDoesNotChangeOnReplayOrFollowup() {
        id=Long.parseLong(app.create(1,null).id());
        assertEquals("AUTO",db.queryForObject("SELECT title_source FROM conversation WHERE id=?",String.class,id));
        var first=send("title-first","帮我生成一个小猫图标");
        assertEquals("帮我生成一个小猫图标",app.send(1,id,first).title());
        assertEquals("帮我生成一个小猫图标",app.send(1,id,first).title());
        assertEquals("帮我生成一个小猫图标",app.list(1).stream().filter(c->c.id().equals(Long.toString(id))).findFirst().orElseThrow().title());
        when(planner.decide(any())).thenReturn(ask());run();
        app.send(1,id,send("title-next","改成蓝色"));
        assertEquals("帮我生成一个小猫图标",app.snapshot(1,id).title());
    }
    // 【测什么】显式标题包括与占位名完全同名的标题不能被自动覆盖。
    // 【怎么算红】只按标题字符串判断自动命名，USER标题被覆盖导致断言失败。
    @Test void explicitTitlesIncludingPlaceholderArePreserved() {
        for(String title:List.of("我的猫咪项目","新的创作")) {
            long conversation=Long.parseLong(app.create(1,title).id());
            assertEquals(title,app.send(1,conversation,send("manual","生成小猫图标")).title());
        }
    }
    // 【测什么】首条中文/Emoji长需求规范空白并按码点截断，标题和消息同事务回滚。
    // 【怎么算红】按UTF16截断会损坏Emoji；标题先于事务提交会残留。
    @Test void titleIsUnicodeSafeAndRollsBackWithMessage() {
        long conversation=Long.parseLong(app.create(1," ").id());
        var session=store.owned(conversation,1,false);
        String content="  猫咪\n\t "+"😀".repeat(40);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{
            store.message(session,null,"USER",content,json.createArrayNode(),"rollback","hash");
            throw new IllegalStateException("rollback");
        }));
        assertEquals("新的创作",store.title(session));
        tx.executeWithoutResult(t->store.message(session,null,"USER",content,json.createArrayNode(),"accepted","hash"));
        String title=store.title(session);
        assertEquals("猫咪 "+"😀".repeat(29),title);
        assertEquals(32,title.codePointCount(0,title.length()));
    }
    // 【测什么】真实V42数据修复取首条用户内容，不取助手/后续确认，保留其他标题和普通/归档对话。
    // 【怎么算红】取最后一条、缺少AGENT/归档过滤或覆盖自定义标题，数据库值断言失败。
    @Test void legacyTitlesBackfillFromInitialContentOnly() {
        long legacy=store.create(1,"新的创作"), named=store.create(1,"我的项目"), empty=store.create(1,"新的创作");
        long ordinary=store.create(1,"新的创作"), archived=store.create(1,"新的创作");
        for(long conversation:List.of(legacy,named,ordinary,archived)) {
            var session=store.owned(conversation,1,false);
            tx.executeWithoutResult(t->{
                store.message(session,null,"ASSISTANT","欢迎创作",json.createArrayNode(),null,null);
                store.message(session,null,"USER","小猫图标\n设计",json.createArrayNode(),"first","h1");
                store.message(session,null,"USER","确认生成",json.createArrayNode(),"second","h2");
            });
        }
        db.update("UPDATE conversation SET creation_mode='DIRECT' WHERE id=?",ordinary);
        db.update("UPDATE conversation SET archived=1 WHERE id=?",archived);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V42__agent_conversation_titles.sql"))
                .execute(Objects.requireNonNull(db.getDataSource()));
        assertEquals("小猫图标 设计",store.title(store.owned(legacy,1,false)));
        assertEquals("我的项目",store.title(store.owned(named,1,false)));
        assertEquals("新的创作",store.title(store.owned(ordinary,1,false)));
        assertEquals("新的创作",db.queryForObject("SELECT title FROM conversation WHERE id=?",String.class,archived));
        assertEquals("AUTO",db.queryForObject("SELECT title_source FROM conversation WHERE id=?",String.class,empty));
        assertEquals("新的创作",store.title(store.owned(empty,1,false)));
        assertEquals("小猫图标 设计",app.list(1).stream().filter(c->c.id().equals(Long.toString(legacy))).findFirst().orElseThrow().title());
    }

    // 【测什么】省略模型自动选择，优先级变化后原请求重放不重选；同轮回答保持原通道。
    // 【怎么算红】把解析后的通道用于hash或WAITING_USER重选，轮次/通道/作业数断言失败。
    @Test void automaticChannelIsPinnedPerTurnAndReplayUsesOriginalPayload() {
        when(models.defaultChannel()).thenReturn("first"); when(planner.decide(any())).thenReturn(ask());
        var request=new AgentApplication.Send("auto","生成宣传片",null);
        var accepted=app.send(1,id,request); assertEquals("first",accepted.turn().channel()); run();
        when(models.defaultChannel()).thenReturn("second");
        assertEquals(accepted.turn().id(),app.send(1,id,request).turn().id());
        var resumed=app.send(1,id,new AgentApplication.Send("reply","科技风",null));
        assertEquals(accepted.turn().id(),resumed.turn().id()); assertEquals("first",resumed.turn().channel());
        verify(models,times(1)).defaultChannel(); assertEquals(1,count("agent_turn")); assertEquals(2,count("job_probe"));
        when(planner.decide(any())).thenReturn(new AgentDecision("RESPOND","收到",null,List.of(),null,null)); run();
        assertEquals("second",app.send(1,id,new AgentApplication.Send("new","新的创作",null)).turn().channel());
    }
    // 【测什么】无可用通道不受理消息；显式旧通道请求不改为自动选择。
    // 【怎么算红】先写消息再选通道或忽略显式channel，计数/通道断言失败。
    @Test void automaticConfigurationFailureHasNoWritesAndExplicitChannelStillWorks() {
        when(models.defaultChannel()).thenThrow(new BusinessException(503,"没有可用 AI 通道"));
        assertEquals(503,assertThrows(BusinessException.class,()->app.send(1,id,new AgentApplication.Send("auto","你好",null))).getCode());
        assertEquals(0,count("agent_turn")); assertEquals(0,count("conversation_message")); assertEquals(0,count("job_probe"));
        assertEquals("self-hosted",app.send(1,id,send("explicit","你好")).turn().channel());
    }
    // 【测什么】本人重复删除幂等，越权/缺失404；删除后所有读写被拒且排队Worker不调用LLM。
    // 【怎么算红】移除ownership、archived过滤或取消fence，404/调用次数/消息保留断言失败。
    @Test void deleteIsOwnerScopedIdempotentAndFencesQueuedWork() {
        app.send(1,id,send("one","生成脚本")); String turn=app.snapshot(1,id).turn().id();
        assertEquals(404,assertThrows(BusinessException.class,()->app.delete(2,id)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->app.delete(1,-1)).getCode());
        app.delete(1,id); long epoch=store.turn(turn).epoch(); app.delete(1,id);
        assertEquals(epoch,store.turn(turn).epoch()); assertEquals("CANCELLED",store.turn(turn).status());
        assertTrue(app.list(1).isEmpty()); assertNull(store.session(store.turn(turn).sessionId()).activeTurnId());
        assertEquals(1,count("conversation_message")); assertEquals(1,count("conversation"));
        assertEquals(404,assertThrows(BusinessException.class,()->app.snapshot(1,id)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->app.send(1,id,send("two","再写"))).getCode());
        run(); verifyNoInteractions(planner); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】LLM调用期间删除对话，迟到决策不能写消息、创建Skill或重排作业。
    // 【怎么算红】删current epoch/active守卫，决策会创建SkillCall或留下运行作业。
    @Test void deleteDuringPlanningDiscardsLateDecision() {
        when(planner.decide(any())).thenAnswer(a->{ app.delete(1,id); return new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode()); });
        app.send(1,id,send("one","生成脚本")); run();
        assertEquals(0,count("agent_decision")); assertEquals(0,count("agent_skill_call")); assertEquals(1,count("conversation_message"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }

    // 【测什么】计划提案必须停下来待采用，采用后的目标替代旧目标并进入后续模型上下文。
    // 【怎么算红】删除PLAN暂停或继续把旧session.goal传给模型，状态或目标断言失败。
    @Test void planProposalPausesAndAdoptedGoalBecomesContext() throws Exception {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","plan",json.createObjectNode(),"PLAN"));
        var data=json.readTree("{\"goal\":\"天津武清雷达科普片\",\"constraints\":[\"科技风\"],\"steps\":[{\"id\":\"script\",\"kind\":\"SCRIPT\",\"title\":\"脚本\"}]}");
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","拟定计划")));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("PLAN","创作计划","先写脚本",null,data,null));
        app.send(1,id,send("plan","之前的目标")); run(); run();
        var snapshot=app.snapshot(1,id); assertEquals("COMPLETED",snapshot.turn().status()); assertEquals(2,count("job_probe"));
        var plan=snapshot.artifacts().get(0);
        assertEquals("PLAN",snapshot.messages().get(2).parts().get(0).path("artifactType").asText());
        var workspace=new org.example.seedancegenarate.agent.application.AgentWorkspaceApplication(store,new AgentApprovalStore(db,store),tx,json,jobs,models);
        workspace.apply(1,id,new org.example.seedancegenarate.agent.application.AgentWorkspaceApplication.Command("adopt",1L,"ADOPT_PLAN",new AgentContext.ArtifactRef(plan.id(),1,null)));
        when(planner.decide(any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0); assertEquals("天津武清雷达科普片",c.goal());
            assertTrue(c.plan().path("confirmed").asBoolean()); assertEquals("script",c.plan().path("currentStepId").asText());
            return new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","写脚本"));
        });
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","雷达脚本","脚本正文",null));
        run(); run();
        // Required output completes the adopted Plan without another model invocation.
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
        assertEquals("SUCCEEDED",store.workspace(store.owned(id,1,false)).path("steps").get(0).path("status").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM job_probe WHERE done=FALSE",Integer.class));
    }
    // 【测什么】未采用计划时，即使LLM请求直接生成脚本也不执行技能。
    // 【怎么算红】移除planConfirmed守卫，多创建SkillCall且状态进入WAITING_SKILL。
    @Test void draftPlanBlocksExecutionBeyondPlanning() {
        var s=store.owned(id,1,false); var w=store.workspace(s);
        var p=store.artifact(s,"draft",null,"PLAN","计划","计划");
        w.set("planRef",json.createObjectNode().put("artifactId",p.id()).put("version",1)); store.saveWorkspace(s,w);
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","写脚本")));
        app.send(1,id,send("m1","直接执行")); run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status()); assertEquals(0,count("agent_skill_call")); verify(skill,never()).execute(any(),any());
    }
    // 【测什么】Planner读取当前图片，Skill上下文读取创建时冻结的图片，不随Session后来变化。
    // 【怎么算红】context的Skill分支改读当前workspace.imageAssetIds时会得到8而非9。
    @Test void imageContextIsFrozenAtSkillCreation() {
        app.send(1,id,send("image-scope","根据图片策划"));
        var session=store.owned(id,1,false);var workspace=store.workspace(session);
        workspace.putArray("imageAssetIds").add("9");store.saveWorkspace(session,workspace);
        when(planner.decide(any())).thenAnswer(a->{
            assertEquals(List.of("9"),((AgentContext)a.getArgument(0)).imageAssetIds());
            return new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","分析图片写脚本"));
        });
        run();
        // Deliberately alter only the mutable projection: the call must still use its frozen IDs.
        workspace.putArray("imageAssetIds").add("8");store.saveWorkspace(session,workspace);
        when(skill.execute(any(),any())).thenAnswer(a->{
            assertEquals(List.of("9"),((AgentContext)a.getArgument(0)).imageAssetIds());
            return new SkillResult("SCRIPT","图片策划","内容",null);
        });
        run();verify(skill).execute(any(),any());
        assertEquals(1,count("agent_artifact_version"));
    }

    // 【测什么】明确引用的旧版本即使不在最近窗口也会完整传入Skill，不能悄悄切到最新版。
    // 【怎么算红】context只读最近作品或selection没采用冻结sourceRef，版本和正文断言失败。
    @Test void explicitSourceIsFrozenAndLoadedBeyondRecentWindow() {
        var s=store.owned(id,1,false); var old=store.artifact(s,"old",null,"SCRIPT","原稿","旧稿全文");
        store.artifact(s,"new",old.id(),"SCRIPT","新稿","不同内容");
        for(int i=0;i<22;i++) store.artifact(s,"filler"+i,null,"SCRIPT","其他","其他");
        var input=json.createObjectNode().put("instruction","参考原稿另写"); input.set("source",json.valueToTree(new AgentContext.ArtifactRef(old.id(),1,null)));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input));
        when(skill.execute(any(),any())).thenAnswer(a->{
            AgentContext c=a.getArgument(0); assertEquals(new AgentContext.ArtifactRef(old.id(),1,null),c.selection());
            assertEquals("旧稿全文",c.artifacts().get(0).content());
            return new SkillResult("SCRIPT","派生稿","派生内容",null,null,c.selection());
        });
        app.send(1,id,send("m1","参考最初的稿件")); run(); run();
        var derived=app.snapshot(1,id).artifacts().get(0); assertNotEquals(old.id(),derived.id());
        assertEquals(old.id(),derived.sourceRef().path("artifactId").asText()); assertEquals(1,derived.sourceRef().path("version").asInt());
    }
    // 【测什么】即使Skill实现返回越界结果，Runtime仍拒绝改动非选中幕或伪造来源版本。
    // 【怎么算红】移除Runtime结果来源/逐幕等值校验，会保存额外的错误版本。
    @Test void runtimeRejectsSkillChangingOtherScenesOrForgingSource() throws Exception {
        var s=store.owned(id,1,false); var board=store.artifact(s,"board",null,"STORYBOARD","分镜","原文");
        var data=json.readTree("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"第一幕\",\"visual\":\"城市\",\"narration\":\"\"},{\"sceneId\":\"s2\",\"title\":\"第二幕\",\"visual\":\"雷达\",\"narration\":\"\"}]}");
        db.update("UPDATE agent_artifact_version SET data_json=?",data.toString());
        var ref=new AgentContext.ArtifactRef(board.id(),1,"s2");
        var input=json.createObjectNode().put("instruction","只改第二幕"); input.set("source",json.valueToTree(ref));
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","board",json.createObjectNode(),"STORYBOARD"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input));
        var changed=data.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("scenes").get(0)).put("visual","不应改变的第一幕");
        when(skill.execute(any(),any())).thenReturn(new SkillResult("STORYBOARD","分镜","错误",board.id(),changed,ref));
        app.send(1,id,send("m1","修改第二幕")); run();
        var job=next(); job.setAttempts(2); runtime.execute(job,true);
        assertEquals("FAILED",app.snapshot(1,id).turn().status()); assertEquals(1,count("agent_artifact_version"));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("STORYBOARD","分镜","错误",board.id(),data,new AgentContext.ArtifactRef(board.id(),1,"s1")));
        app.send(1,id,send("m2","再次修改第二幕")); run(); job=next(); job.setAttempts(2); runtime.execute(job,true);
        assertEquals("FAILED",app.snapshot(1,id).turn().status()); assertEquals(1,count("agent_artifact_version"));
    }

    // 【测什么】完整无变化结果经过真实Runtime来源守卫后复用版本，留下UNCHANGED而不是伪称改稿成功。
    // 【怎么算红】新增版本/无观察/无后续规划入口会使持久行数、观察和作业断言失败。
    @Test void unchangedStoryboardResultIsObservedAndCanFinishWithoutAnotherEdit()throws Exception {
        var s=store.owned(id,1,false);var board=store.artifact(s,"board",null,"STORYBOARD","分镜","原文");
        var data=json.readTree("{\"scenes\":[{\"sceneId\":\"s1\",\"title\":\"幕\",\"visual\":\"城市\",\"narration\":\"\",\"duration\":6}]}");
        db.update("UPDATE agent_artifact_version SET data_json=? WHERE artifact_id=?",data.toString(),board.id());
        var ref=new AgentContext.ArtifactRef(board.id(),1,"s1");
        var input=json.createObjectNode().put("instruction","第一幕6秒");input.set("source",json.valueToTree(ref));
        when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","board",json.createObjectNode(),"STORYBOARD"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",input));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("STORYBOARD","分镜","原文",board.id(),data,ref));
        app.send(1,id,send("same","第一幕6秒"));run();run();
        assertEquals(1,count("agent_artifact_version"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_observation WHERE code='UNCHANGED'",Integer.class));
        when(planner.decide(any())).thenReturn(new AgentDecision("COMPLETE","当前第一幕已为6秒，原稿保留。",null,List.of(),null,null));run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());verify(skill,times(1)).execute(any(),any());
    }

    // 【测什么】消息提交仅创建持久化作业，重复请求只重放一次；修改同键内容拒绝。
    // 【怎么算红】移除requestHash检查后第二次消息冲突或多出作业，本测试失败。
    @Test void acceptanceIsAsyncAndIdempotent() {
        app.send(1,id,send("m1","生成宣传片脚本")); app.send(1,id,send("m1","生成宣传片脚本"));
        assertEquals(1,count("conversation_message")); assertEquals(1,count("job_probe")); verifyNoInteractions(planner);
        assertThrows(BusinessException.class,()->app.send(1,id,send("m1","不同内容")));
        assertThrows(BusinessException.class,()->app.send(1,id,send("m2","同时发送")));
        assertThrows(BusinessException.class,()->app.snapshot(2,id));
    }
    // 【测什么】入队失败必须回滚消息、Turn和Session的推进。
    // 【怎么算红】移除受理事务后会残留消息或Turn，计数断言失败。
    @Test void enqueueFailureRollsBackAcceptance() {
        doThrow(new IllegalStateException("database write failed")).when(jobs).enqueue(anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->app.send(1,id,send("m1","脚本")));
        assertEquals(0,count("conversation_message")); assertEquals(0,count("agent_turn"));
        assertNull(app.snapshot(1,id).turn());
    }
    // 【测什么】后端重建后选择仍可恢复同一Turn，双击选择只推进一次并保留已确认上下文。
    // 【怎么算红】移除交互版本/幂等检查或丢弃confirmedChoices，断言失败。
    @Test void persistedChoiceResumesAcrossRuntimeRecreation() {
        when(planner.decide(any())).thenReturn(ask()); app.send(1,id,send("m1","武清气象宣传片")); run();
        var before=app.snapshot(1,id); String interaction=before.messages().get(1).parts().get(0).path("interactionId").asText();
        assertEquals("WAITING_USER",before.turn().status());
        assertThrows(BusinessException.class,()->app.answer(1,id,interaction,new AgentApplication.Answer("a0","invalid",1)));
        var answer=new AgentApplication.Answer("a1","a",1); app.answer(1,id,interaction,answer); app.answer(1,id,interaction,answer);
        assertEquals(2,count("job_probe")); assertEquals(before.turn().id(),app.snapshot(1,id).turn().id());
        assertThrows(BusinessException.class,()->app.answer(1,id,interaction,new AgentApplication.Answer("a2","b",1)));
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models);
        when(planner.decide(any())).thenAnswer(a->{AgentContext c=a.getArgument(0); assertTrue(c.confirmedChoices().get(0).contains("科技风"));return new AgentDecision("RESPOND","好的",null,List.of(),null,null);});
        run(); assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】停止时递增epoch，已经发出的模型请求迟到后不能落消息或继续Skill。
    // 【怎么算红】删除current()取消/epoch检查，迟到的ASK_USER会覆盖CANCELLED。
    @Test void cancelledLateModelResultCannotCommit() {
        app.send(1,id,send("m1","脚本")); when(planner.decide(any())).thenAnswer(a->{app.cancel(1,id,app.snapshot(1,id).turn().id());return ask();});
        run(); assertEquals("CANCELLED",app.snapshot(1,id).turn().status()); assertEquals(1,count("conversation_message"));
        assertEquals(0,count("agent_decision")); assertEquals(0,count("agent_interaction"));
    }
    // 【测什么】Worker租约丢失后不得写结果；另一实例拿到租约可恢复同一持久化步骤。
    // 【怎么算红】同时移除提交前续租和complete(false)回滚两道守卫，旧Worker会写入交互。
    @Test void lostLeaseCannotCommitButReclaimedWorkCanResume() {
        app.send(1,id,send("m1","脚本")); when(planner.decide(any())).thenAnswer(a->{leaseValid.set(false);return ask();});
        assertThrows(IllegalStateException.class,this::run); assertEquals(0,count("agent_decision"));
        doReturn(ask()).when(planner).decide(any()); leaseValid.set(true);
        runtime=new AgentRuntime(store,jobs,tx,planner,skills,json,mock(AgentGenerationRuntime.class),diagnostics,models); run();
        assertEquals("WAITING_USER",app.snapshot(1,id).turn().status()); assertEquals(1,count("agent_decision"));
    }
    // 【测什么】技能结果有独立版本产物，重复回调不写第二份，完成后自动排下一步。
    // 【怎么算红】移除call状态或step守卫，重复执行旧job时产物/后续作业计数改变。
    @Test void skillCreatesArtifactOnceAndAutomaticallyResumes() {
        app.send(1,id,send("m1","生成脚本"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","写脚本")));
        run(); assertEquals("WAITING_SKILL",app.snapshot(1,id).turn().status());
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","武清气象","第二幕：雷达",null));
        AsyncJob job=next(); run(); runtime.execute(job,true);
        assertEquals(1,count("agent_artifact_version")); assertEquals(3,count("job_probe"));
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());
        when(planner.decide(any())).thenReturn(new AgentDecision("COMPLETE","脚本完成",null,List.of(),null,null)); run();
        assertEquals("COMPLETED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】旧版本不覆盖，也不能借作品ID修改别人的作品。
    // 【怎么算红】去掉version递增或归属校验，版本列表/越权异常断言失败。
    @Test void artifactVersionsAreImmutableAndOwned() {
        var s=store.owned(id,1,false);
        var a=tx.execute(t->store.artifact(s,"c1",null,"SCRIPT","初版","原文"));
        tx.execute(t->store.artifact(s,"c2",a.id(),"SCRIPT","新版","修改"));
        assertEquals(List.of(2,1),store.artifacts(s).stream().map(v->v.version()).toList());
        long other=Long.parseLong(app.create(2,"其他").id());
        assertThrows(BusinessException.class,()->tx.execute(t->store.artifact(store.owned(other,2,true),"c3",a.id(),"SCRIPT","越权","内容")));
    }
    // 【测什么】排队期间超过旧deadline的Planner获得租约后可执行，不把停机等待当调用超时。
    // 【怎么算红】begin在建立模型操作预算前拒绝所有过期Turn时，状态为FAILED而非WAITING_USER。
    @Test void queuedPlannerStartsWithFreshExecutionDeadline() {
        app.send(1,id,send("m1","脚本"));
        when(planner.decide(any())).thenReturn(ask());
        db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(MINUTE,-1,NOW())");run();
        assertEquals("WAITING_USER",app.snapshot(1,id).turn().status());
        verify(planner,times(1)).decide(any());
        assertEquals(1,db.queryForObject("SELECT attempt_count FROM agent_model_recovery",Integer.class));
    }
    // 【测什么】Planner之后排队的文本Skill也有独立期限，不丢失已决定但尚未执行的步骤。
    // 【怎么算红】只给Planner放行旧deadline时，文本Skill不会生成作品。
    @Test void queuedModelSkillStartsWithFreshExecutionDeadline() {
        app.send(1,id,send("queued-skill","脚本"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"script-generation",json.createObjectNode().put("instruction","脚本")));
        when(skill.execute(any(),any())).thenReturn(new SkillResult("SCRIPT","脚本","正文",null));
        run();db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(MINUTE,-1,NOW())");run();
        assertEquals("QUEUED",app.snapshot(1,id).turn().status());assertEquals(1,count("agent_artifact_version"));
        verify(skill,times(1)).execute(any(),any());
        assertEquals(1,db.queryForObject("SELECT attempt_count FROM agent_model_recovery WHERE phase='TEXT_SKILL'",Integer.class));
    }
    // 【测什么】没有持久化尝试预算的普通Skill仍保留原deadline，不全局延长所有工作。
    // 【怎么算红】无条件忽略begin deadline会执行已过期的普通插件。
    @Test void nonModelSkillRetainsDeadlineGuard() {
        when(skill.descriptor()).thenReturn(new SkillDescriptor("custom-text","1","custom",json.createObjectNode()));
        runtime=new AgentRuntime(store,jobs,tx,planner,new SkillRegistry(List.of(skill)),json,mock(AgentGenerationRuntime.class),diagnostics,models);
        app.send(1,id,send("custom","脚本"));
        when(planner.decide(any())).thenReturn(new AgentDecision("CALL_SKILL",null,null,List.of(),"custom-text",json.createObjectNode().put("instruction","脚本")));
        run();db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(MINUTE,-1,NOW())");run();
        assertEquals("FAILED",app.snapshot(1,id).turn().status());verify(skill,never()).execute(any(),any());
    }
    // 【测什么】实际开始后的模型操作仍检查deadline，不接收超时结果。
    // 【怎么算红】删除finishDecision的deadline守卫会把超时回复变成WAITING_USER。
    @Test void runningPlannerDeadlineStillRejectsLateResult() {
        app.send(1,id,send("late","脚本"));
        when(planner.decide(any())).thenAnswer(a->{
            db.update("UPDATE agent_turn SET deadline_at=TIMESTAMPADD(MINUTE,-1,NOW())");return ask();
        });
        run();assertEquals("FAILED",app.snapshot(1,id).turn().status());assertEquals(0,count("agent_interaction"));
    }
    // 【测什么】未知非恢复型异常仍终止，不因排队deadline修复变成无界重投。
    // 【怎么算红】忽略永久异常会留下可再次执行的状态。
    @Test void unknownRuntimeFailureStillTerminates() {
        app.send(1,id,send("m2","脚本")); when(planner.decide(any())).thenThrow(new IllegalStateException("invalid response"));
        AsyncJob job=next(); job.setAttempts(2); runtime.execute(job,false);
        assertEquals("FAILED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】过期问题不再显示可选状态，刷新落库失败终态并允许重新发送。
    // 【怎么算红】移除expireQuestion，snapshot仍是WAITING_USER/PENDING而断言失败。
    @Test void expiredChoiceBecomesDurableTerminalState() {
        app.send(1,id,send("m1","脚本")); when(planner.decide(any())).thenReturn(ask()); run();
        db.update("UPDATE agent_interaction SET expires_at=TIMESTAMPADD(SECOND,-1,NOW())");
        var snapshot=app.snapshot(1,id);
        assertEquals("FAILED",snapshot.turn().status());
        assertEquals("EXPIRED",snapshot.messages().get(1).parts().get(0).path("status").asText());
        app.send(1,id,send("m2","换成纪录片")); assertEquals("QUEUED",app.snapshot(1,id).turn().status());
    }
    // 【测什么】迟到的停止请求只能取消当时那一轮，不能误停后来新建的任务。
    // 【怎么算红】移除expectedTurn相等检查，新一轮会变CANCELLED。
    @Test void delayedCancellationCannotStopNewTurn() {
        var first=app.send(1,id,send("m1","第一版")); app.cancel(1,id,first.turn().id());
        var second=app.send(1,id,send("m2","第二版")); app.cancel(1,id,first.turn().id());
        assertEquals(second.turn().id(),app.snapshot(1,id).turn().id()); assertEquals("QUEUED",app.snapshot(1,id).turn().status());
    }
}
