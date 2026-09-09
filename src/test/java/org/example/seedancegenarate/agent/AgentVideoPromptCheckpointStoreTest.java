package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation.*;
import org.example.seedancegenarate.agent.persistence.AgentVideoPromptCheckpointStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import static org.example.seedancegenarate.agent.persistence.AgentRows.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentVideoPromptCheckpointStoreTest {
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate db;TransactionTemplate tx;AgentVideoPromptCheckpointStore store;
    Session session;Turn turn;Call call;
    Plan plan(String hash,String step) {
        return new Plan(hash,step,List.of(new Scene("scene-1",1,json.nullNode(),null,json.nullNode(),"guide"),
                new Scene("scene-2",2,json.nullNode(),null,json.nullNode(),"guide")),4000);
    }
    Plan plan() {return plan("a".repeat(64),"plan-step");}
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:prompt"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new AgentVideoPromptCheckpointStore(db);
        db.execute("CREATE TABLE agent_session(id VARCHAR(64) PRIMARY KEY,user_id BIGINT,active_turn_id VARCHAR(64))");
        db.execute("CREATE TABLE agent_turn(id VARCHAR(64) PRIMARY KEY,session_id VARCHAR(64),epoch BIGINT,step_no INT,status VARCHAR(32))");
        db.execute("CREATE TABLE agent_skill_call(id VARCHAR(64) PRIMARY KEY,turn_id VARCHAR(64),epoch BIGINT,step_no INT,skill_id VARCHAR(64),status VARCHAR(32))");
        db.execute("CREATE TABLE agent_approval(id VARCHAR(64) PRIMARY KEY,call_id VARCHAR(64))");
        String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V52__agent_video_prompt_checkpoint.sql")).readAllBytes(),StandardCharsets.UTF_8)
                .replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V53__agent_video_prompt_repair.sql")).execute(ds);
        session=new Session("session",1,1,0,null,null,"turn");
        turn=new Turn("turn","session","llm","RUNNING",0,1,null,LocalDateTime.now().plusMinutes(5));
        call=new Call("call","turn","video-generation","1","{}","RUNNING",1,0);
        db.update("INSERT INTO agent_session VALUES('session',1,'turn')");
        db.update("INSERT INTO agent_turn VALUES('turn','session',1,0,'RUNNING')");
        db.update("INSERT INTO agent_skill_call VALUES('call','turn',1,0,'video-generation','RUNNING')");
    }
    Map<String,String> load(Plan plan) {return tx.execute(t->store.loadOrCreate(session,turn,call,plan));}
    void save(Plan plan,int index,String prompt) {tx.executeWithoutResult(t->store.save(session,turn,call,plan,plan.scenes().get(index),prompt));}
    void nextCall() {
        db.update("UPDATE agent_skill_call SET status='SUSPENDED' WHERE id=?",call.id());
        int next=turn.step()+1;db.update("UPDATE agent_turn SET step_no=? WHERE id=?",next,turn.id());
        turn=new Turn(turn.id(),"session","llm","RUNNING",next,turn.epoch(),null,turn.deadline());
        call=new Call(turn.id()+"-call-"+next,turn.id(),"video-generation","1","{}","RUNNING",turn.epoch(),next);
        db.update("INSERT INTO agent_skill_call VALUES(?,?,?,?,?,'RUNNING')",call.id(),turn.id(),turn.epoch(),next,"video-generation");
    }
    // 【测什么】修正资格与入队事务共同回滚，重建Store不重置资格，已审批之后不可再申请。
    // 【怎么算红】删除repair_count=0或current审批守卫，重复预约/已审批预约将错误返回true。
    @Test void repairReservationIsDurableBoundedAndRollsBackWithScheduling() {
        load(plan());
        var error=org.example.seedancegenarate.agent.generation.VideoPreparationException.invalid(
                org.example.seedancegenarate.agent.generation.VideoPreparationException.ValidationRule.JSON_INVALID,"$")
                .atScene(1).withDiagnosticId("diagnostic-1");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{
            store.recordFailure(session,turn,call,error);
            assertTrue(store.reserveRepair(session,turn,call,1));
            throw new IllegalStateException("enqueue failed");
        }));
        assertEquals(0,db.queryForObject("SELECT repair_count FROM agent_video_prompt_checkpoint WHERE ordinal=1",Integer.class));
        tx.executeWithoutResult(t->{store.recordFailure(session,turn,call,error);assertTrue(store.reserveRepair(session,turn,call,1));});
        store=new AgentVideoPromptCheckpointStore(db);
        assertEquals(Boolean.FALSE,tx.execute(t->store.reserveRepair(session,turn,call,1)));
        assertEquals(error.repairHint(),store.repairHint(call,plan().scenes().get(0)));
        assertNull(store.repairHint(call,plan().scenes().get(1)));
        db.update("INSERT INTO agent_approval VALUES('approval','call')");
        assertThrows(BusinessException.class,()->tx.execute(t->store.reserveRepair(session,turn,call,2)));
    }
    // 【测什么】已完成一幕重建Store仍复用，未完成幕不伪装完成，进度与下一作业身份稳定。
    // 【怎么算红】去掉prompt持久化或按总数生成nextJobKey，恢复字节/1比2进度/prepare:1断言失败。
    @Test void completedSceneSurvivesStoreRecreationAndAdvancesOnlyOnce() {
        assertTrue(load(plan()).isEmpty());assertEquals("call",store.nextJobKey(call));save(plan(),0,"passed-first");
        store=new AgentVideoPromptCheckpointStore(db);
        assertEquals(Map.of("scene-1","passed-first"),load(plan()));assertEquals("call:prepare:1",store.nextJobKey(call));
        assertEquals(Map.of("completed",1,"total",2),store.progress(turn));
        save(plan(),0,"passed-first");assertThrows(BusinessException.class,()->save(plan(),0,"overwrite"));
        assertEquals("call:prepare:1",store.nextJobKey(call));
    }
    // 【测什么】人工恢复仅同会话/当前epoch/计划步/完整输入哈希且未审批的旧成功项可被显式复制。
    // 【怎么算红】删掉跨call复制则scene-1丢失，复制空项则Map包含未通过scene-2而失败。
    @Test void explicitSameStepResumeCopiesOnlyPassedUnapprovedText() {
        load(plan());save(plan(),0,"passed-first");nextCall();
        assertEquals(Map.of("scene-1","passed-first"),load(plan()));
        assertEquals("turn-call-1:prepare:1",store.nextJobKey(call));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM agent_video_prompt_checkpoint",Integer.class));
    }
    // 【测什么】真实preparePlan的批次绑定不随恢复时Planner首幕草稿措辞变化，成功幕可复制；真实规格变化仍拒复用。
    // 【怎么算红】将批次plannerDraft重新加入模型输入/绑定，新Call会失去已保存首幕；去掉duration绑定则不同规格错误复用。
    @Test void actualBatchBindingAllowsResumeDespiteNewPlannerDraftButRejectsNewSpecification() {
        var gateway=org.mockito.Mockito.mock(org.example.seedancegenarate.agent.generation.AgentGenerationGateway.class);
        org.mockito.Mockito.when(gateway.videoParameters(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(a->((com.fasterxml.jackson.databind.JsonNode)a.getArgument(0)).deepCopy());
        var preparation=new org.example.seedancegenarate.agent.generation.AgentVideoPromptPreparation(gateway,
                org.mockito.Mockito.mock(org.example.seedancegenarate.agent.model.AgentModelGateway.class),
                new org.example.seedancegenarate.service.PromptTemplateService(),json);
        var board=json.createObjectNode();var scenes=board.putArray("scenes");
        scenes.addObject().put("sceneId","s1").put("visual","仙石崩裂").put("duration",5);
        scenes.addObject().put("sceneId","s2").put("visual","石猴入洞").put("duration",5);
        var context=new org.example.seedancegenarate.agent.model.AgentContext(1L,"session","turn","llm","西游漫剧",null,List.of(),
                List.of(new org.example.seedancegenarate.agent.model.AgentContext.ArtifactContext("board",1,"STORYBOARD","西游记","",board)),
                0,List.of(),null,new org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef("board",1,"s1"));
        java.util.function.BiFunction<String,Integer,org.example.seedancegenarate.agent.skill.TaskQuote> quote=(prompt,duration)->
                new org.example.seedancegenarate.agent.skill.TaskQuote("comfyui","minimax-h3-t2v-hd","H3","VIDEO",
                        json.createObjectNode().put("prompt",prompt).put("duration",duration).put("ratio","16:9"),java.math.BigDecimal.ONE,"CNY");
        java.util.function.IntFunction<org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Prepared> batch=duration->
                new org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Prepared("plan-step",List.of(
                        new org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Item("scene-1",1,
                                json.createObjectNode().put("artifactId","board").put("version",1).put("sceneId","s1"),quote.apply("仙石崩裂",duration)),
                        new org.example.seedancegenarate.agent.runtime.AgentBatchRuntime.Item("scene-2",2,
                                json.createObjectNode().put("artifactId","board").put("version",1).put("sceneId","s2"),quote.apply("石猴入洞",duration))));
        var original=preparation.preparePlan(context,quote.apply("正在制作仙石化生",5),batch.apply(5));
        load(original);save(original,0,"passed-first");nextCall();
        var resumed=preparation.preparePlan(context,quote.apply("重新制作第一幕仙石化生",5),batch.apply(5));
        assertEquals(original.bindingHash(),resumed.bindingHash(),"纯Planner草稿措辞不得使同一批次所有成功幕失效");
        assertEquals(Map.of("scene-1","passed-first"),load(resumed));
        db.update("UPDATE agent_turn SET status='YIELDED' WHERE id=?",turn.id());
        db.update("INSERT INTO agent_turn VALUES('continued-turn','session',1,0,'RUNNING')");
        db.update("UPDATE agent_session SET active_turn_id='continued-turn' WHERE id='session'");
        db.update("INSERT INTO agent_skill_call VALUES('continued-call','continued-turn',1,0,'video-generation','RUNNING')");
        session=new Session("session",1,1,0,null,null,"continued-turn");
        turn=new Turn("continued-turn","session","llm","RUNNING",0,1,null,turn.deadline());
        call=new Call("continued-call","continued-turn","video-generation","1","{}","RUNNING",1,0);
        assertEquals(Map.of("scene-1","passed-first"),load(resumed));
        nextCall();
        var changed=preparation.preparePlan(context,quote.apply("重新制作第一幕仙石化生",10),batch.apply(10));
        assertNotEquals(original.bindingHash(),changed.bindingHash());assertTrue(load(changed).isEmpty());
    }
    // 【测什么】任何绑定变化都不能在原call继续使用旧结果，新call的新条件也不继承旧准备文本。
    // 【怎么算红】移除binding_hash或plan_step_id比较/复制条件，这些不同条件会错误读到passed-first。
    @Test void changedInputsOrPlanStepInvalidateReuse() {
        load(plan());save(plan(),0,"passed-first");
        assertThrows(BusinessException.class,()->load(plan("b".repeat(64),"plan-step")));
        nextCall();assertTrue(load(plan("b".repeat(64),"plan-step")).isEmpty());
        nextCall();assertTrue(load(plan("a".repeat(64),"another-step")).isEmpty());
    }
    // 【测什么】已存在Approval的call不能重写提示词，也不能成为新call的文本恢复来源。
    // 【怎么算红】移除任一NOT EXISTS approval守卫，写入或新call复用断言失败。
    @Test void anyApprovalPermanentlyClosesPreparationAndReuse() {
        load(plan());save(plan(),0,"passed-first");db.update("INSERT INTO agent_approval VALUES('approval','call')");
        assertThrows(BusinessException.class,()->save(plan(),1,"late-second"));
        assertThrows(BusinessException.class,()->load(plan()));
        nextCall();assertTrue(load(plan()).isEmpty());
    }
    // 【测什么】取消、旧epoch、旧call与其他会话不能写入迟到结果；失败call的已完成数仍可读。
    // 【怎么算红】移除current的active turn/epoch/step条件，迟到save被接受；漏FAILED则进度消失。
    @Test void cancellationEpochAndOldCallFenceLateResults() {
        load(plan());save(plan(),0,"passed-first");
        db.update("UPDATE agent_turn SET status='CANCELLED',epoch=2 WHERE id='turn'");
        assertThrows(BusinessException.class,()->save(plan(),1,"late-second"));
        db.update("UPDATE agent_turn SET status='RUNNING',epoch=1 WHERE id='turn'");
        db.update("UPDATE agent_skill_call SET status='FAILED' WHERE id='call'");
        assertEquals(Map.of("completed",1,"total",2),store.progress(turn));
        assertThrows(BusinessException.class,()->save(plan(),1,"late-second"));
        db.update("UPDATE agent_skill_call SET status='RUNNING' WHERE id='call'");
        Call old=call;nextCall();
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(t->store.save(session,turn,old,plan(),plan().scenes().get(1),"late-second")));
        assertNull(store.progress(turn));
    }
    // 【测什么】实际Runtime WAITING_SKILL能创建/复制/保存准备项，重建Store仍可读取复制结果。
    // 【怎么算红】current遗漏WAITING_SKILL会409；复制后重建丢失准备项则字节断言失败。
    @Test void waitingSkillAndResumeCopyRemainDurable() {
        db.update("UPDATE agent_turn SET status='WAITING_SKILL' WHERE id='turn'");
        load(plan());save(plan(),0,"passed-first");nextCall();
        assertEquals(Map.of("scene-1","passed-first"),load(plan()));
        store=new AgentVideoPromptCheckpointStore(db);
        assertEquals(Map.of("scene-1","passed-first"),load(plan()));
        save(plan(),1,"passed-second");assertEquals(2,load(plan()).size());
    }
    // 【测什么】新epoch与其他session即使输入哈希相同，也不能复制旧的成功提示词。
    // 【怎么算红】删除复用SQL的epoch或session约束，新的准备会错误包含passed-first。
    @Test void newEpochAndDifferentSessionDoNotCopy() {
        load(plan());save(plan(),0,"passed-first");
        db.update("UPDATE agent_turn SET epoch=2 WHERE id='turn'");
        turn=new Turn("turn","session","llm","RUNNING",0,2,null,turn.deadline());nextCall();
        assertTrue(load(plan()).isEmpty());
        db.update("INSERT INTO agent_session VALUES('other',2,'other-turn')");
        db.update("INSERT INTO agent_turn VALUES('other-turn','other',1,0,'RUNNING')");
        db.update("INSERT INTO agent_skill_call VALUES('other-call','other-turn',1,0,'video-generation','RUNNING')");
        session=new Session("other",2,2,0,null,null,"other-turn");
        turn=new Turn("other-turn","other","llm","RUNNING",0,1,null,turn.deadline());
        call=new Call("other-call","other-turn","video-generation","1","{}","RUNNING",1,0);
        assertTrue(load(plan()).isEmpty());
    }
}
