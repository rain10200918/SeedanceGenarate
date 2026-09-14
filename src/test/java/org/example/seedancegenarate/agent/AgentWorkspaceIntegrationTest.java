package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.AgentWorkspaceApplication;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.service.AsyncJobService;
import static org.mockito.Mockito.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.skill.*;
import org.example.seedancegenarate.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQL/transaction contract fixture, not a production MySQL locking rehearsal. */
class AgentWorkspaceIntegrationTest {
    final ObjectMapper json=new ObjectMapper(); JdbcTemplate db; AgentStore store; AgentApprovalStore approvals;
    AgentWorkspaceApplication app; TransactionTemplate tx; long id;
    final AsyncJobService jobs=mock(AsyncJobService.class);
    final AgentModelGateway models=mock(AgentModelGateway.class);
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:p3"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String file:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V40__agent_model_recovery.sql","V43__agent_turn_lifecycle.sql","V44__agent_output_repair.sql","V45__agent_scene_progress.sql","V47__agent_model_binding.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql","V54__agent_skill_output_repair.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        when(models.defaultChannel()).thenReturn("local");
        store=new AgentStore(db,json); approvals=new AgentApprovalStore(db,store); app=new AgentWorkspaceApplication(store,approvals,tx,json,jobs,models);
        id=tx.execute(t->store.create(1,"宣传片"));
    }
    AgentRows.Session session() { return store.owned(id,1,false); }
    AgentRows.Call call(String kind) {
        var s=session(); String turn=store.newTurn(s,"local","宣传片");
        return store.call(store.newCall(store.turn(turn),kind,"1",json.createObjectNode()));
    }
    org.example.seedancegenarate.agent.api.AgentViews.Artifact plan() {
        return tx.execute(t->store.recordResult(session(),call("creative-plan"),new SkillResult("PLAN","宣传片计划","计划",null,
                json.valueToTree(Map.of("goal","宣传片","constraints",List.of("科技风"),"steps",List.of(Map.of("id","s1","title","脚本","kind","SCRIPT"),Map.of("id","s2","title","分镜","kind","STORYBOARD"),Map.of("id","s3","title","画面","kind","IMAGE")))),null)));
    }
    void apply(String key,long version,String action,ArtifactRef ref) { app.apply(1,id,new AgentWorkspaceApplication.Command(key,version,action,ref)); }
    // 【测什么】普通分镜完整相同结果不增版本、不改旧来源；真实改稿生成新版本，旧版本仍拒绝。
    // 【怎么算红】无条件写版本或在最新版本检查前去重会使版本/行数/冲突断言失败。
    @Test void unchangedOrdinaryStoryboardReusesOnlyExactLatestVersion() {
        var data=json.createObjectNode();data.putArray("scenes").addObject().put("sceneId","s1").put("title","幕").put("visual","猫走路").put("narration","").put("duration",6);
        var original=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","分镜","正文",null,data,null)));
        var ref=new ArtifactRef(original.id(),1,"s1");
        var same=new SkillResult("STORYBOARD","分镜","正文",original.id(),data,ref);
        var result=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),same));
        assertEquals(1,result.version());assertEquals(original.sourceRef(),result.sourceRef());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_artifact_version WHERE artifact_id=?",Integer.class,original.id()));
        var changed=data.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("scenes").get(0)).put("visual","猫进门");
        var edited=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","分镜","新正文",original.id(),changed,ref)));
        assertEquals(2,edited.version());
        assertThrows(BusinessException.class,()->tx.execute(t->store.recordResult(session(),call("storyboard-generation"),same)));
    }
    // 【测什么】有执行计划/正式scene-edit上下文时不进入普通编辑去重分支，保留其版本状态机。
    // 【怎么算红】去重忽略context边界会复用v1，破坏必须v+1的修复协议。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"sceneEditId","executionPlanId"})
    void executionEditRetainsVersionTransitionForIdenticalResult(String contextField) {
        var data=json.createObjectNode();data.putArray("scenes").addObject().put("sceneId","s1").put("title","幕").put("visual","猫").put("narration","").put("duration",6);
        var original=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","分镜","正文",null,data,null)));
        var edit=call("storyboard-generation");
        db.update("UPDATE agent_skill_call SET context_json=? WHERE id=?",json.createObjectNode().put(contextField,"test-edit").toString(),edit.id());
        var result=tx.execute(t->store.recordResult(session(),edit,new SkillResult("STORYBOARD","分镜","正文",original.id(),data,new ArtifactRef(original.id(),1,"s1"))));
        assertEquals(2,result.version());
    }
    // 【测什么】采用计划是持久化确认并入队；响应丢失重放同命令只记一次。
    // 【怎么算红】移除requestHash重放检查，重复采用将409或多增version，断言失败。
    @Test void adoptIsDurableIdempotentAndVersioned() {
        var p=plan(); assertFalse(store.workspace(session()).path("planConfirmed").asBoolean());
        var ref=new ArtifactRef(p.id(),p.version(),null);
        apply("adopt",1,"ADOPT_PLAN",ref); apply("adopt",1,"ADOPT_PLAN",ref);
        app=new AgentWorkspaceApplication(new AgentStore(db,json),approvals,tx,json,jobs,models);
        assertTrue(store.workspace(session()).path("planConfirmed").asBoolean());
        assertEquals(2,store.workspace(session()).path("version").asInt());
        assertEquals("s1",store.workspace(session()).path("currentStepId").asText());
        assertThrows(BusinessException.class,()->apply("adopt",1,"STOP_PLAN",null));
        assertThrows(BusinessException.class,()->apply("stale",1,"STOP_PLAN",null));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM conversation_message",Integer.class));
    }
    // 【测什么】跨用户作品及不存在的幕不能进入选择状态。
    // 【怎么算红】删去artifactVersion的session/user条件或sceneId查验，该异常断言失败。
    @Test void selectionIsScopedAndSceneValidated() {
        long other=store.create(2,"他人"); var s=store.owned(other,2,false);
        var foreign=store.artifact(s,"other",null,"SCRIPT","私有","私有");
        assertThrows(BusinessException.class,()->apply("foreign",0,"SELECT",new ArtifactRef(foreign.id(),1,null)));
        var p=plan(); assertThrows(BusinessException.class,()->apply("scene",1,"SELECT",new ArtifactRef(p.id(),1,"scene-1")));
        assertEquals(1,store.workspace(session()).path("version").asInt());
    }
    // 【测什么】工作区改变取消待授权和已授权未提交审批，但不撤销已跨提交边界的任务。
    // 【怎么算红】删除approvals.cancel或扩大到SUBMITTING，审批状态断言失败。
    @Test void workspaceChangeInvalidatesPendingButPreservesSubmitted() {
        var c=call("image-generation"); var t=store.turn(c.turnId());
        var quote=new TaskQuote("p","m","模型","IMAGE",json.createObjectNode(),java.math.BigDecimal.ONE,"CNY");
        String a=approvals.create(session(),t,c,quote); store.status(t.id(),"WAITING_APPROVAL",null);
        apply("select",0,"SELECT",null); assertEquals("CANCELLED",approvals.get(a).status()); assertEquals("CANCELLED",store.turn(t.id()).status());
        c=call("image-generation"); t=store.turn(c.turnId()); a=approvals.create(session(),t,c,quote);
        approvals.status(a,"SUBMITTING",null); store.status(t.id(),"SUBMITTING",null);
        apply("stop",1,"STOP_PLAN",null); assertEquals("SUBMITTING",approvals.get(a).status()); assertEquals("CANCELLED",store.turn(t.id()).status());
    }
    // 【测什么】精确选中的旧作品不会被20条最近作品窗口挤掉。
    // 【怎么算红】artifacts只返回最近20条，selected版本查询断言失败。
    @Test void pinnedArtifactSurvivesRecentWindow() {
        var a=store.artifact(session(),"first",null,"SCRIPT","第一稿","内容");
        apply("select",0,"SELECT",new ArtifactRef(a.id(),1,null));
        for(int i=0;i<25;i++) store.artifact(session(),"later"+i,null,"SCRIPT","后来","内容");
        assertEquals(20,store.artifacts(session()).size()); assertEquals(a.id(),store.artifacts(session()).get(0).id());
    }
    // 【测什么】仅匹配类型推进步骤，修订分镜保留老稿、切换选择并重置后续步骤。
    // 【怎么算红】删除kind比较、版本CAS或分镜重置，步进/版本/选择断言失败。
    @Test void progressAndRevisionsRemainBoundToExactArtifacts() {
        var p=plan(); apply("adopt",1,"ADOPT_PLAN",new ArtifactRef(p.id(),1,null));
        var wrong=tx.execute(t->store.recordResult(session(),call("image-generation"),new SkillResult("IMAGE","跑偏","图片",null)));
        assertNull(wrong.stepId()); assertEquals("s1",store.workspace(session()).path("currentStepId").asText());
        var script=tx.execute(t->store.recordResult(session(),call("script-generation"),new SkillResult("SCRIPT","脚本","第二幕雷达",null)));
        assertEquals("s1",script.stepId()); assertEquals("s2",store.workspace(session()).path("currentStepId").asText());
        var data=json.valueToTree(Map.of("scenes",List.of(Map.of("sceneId","s1","title","开场","visual","天空","narration",""),Map.of("sceneId","s2","title","雷达","visual","雷达","narration",""))));
        var board=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","分镜","分镜",null,data,new ArtifactRef(script.id(),1,null))));
        assertEquals("s3",store.workspace(session()).path("currentStepId").asText());
        assertEquals(script.id(),board.sourceRef().path("artifactId").asText());
        apply("select",2,"SELECT",new ArtifactRef(board.id(),1,"s2"));
        tx.execute(t->store.recordResult(session(),call("image-generation"),new SkillResult("IMAGE","旧图","图片",null)));
        assertTrue(store.workspace(session()).path("currentStepId").isNull());
        var updated=data.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)updated.path("scenes").get(1)).put("visual","科技雷达");
        var revised=tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","新版分镜","新版",board.id(),updated,new ArtifactRef(board.id(),1,"s2"))));
        assertEquals(2,revised.version()); assertEquals("雷达",store.artifactVersion(session(),board.id(),1).data().path("scenes").get(1).path("visual").asText());
        var w=store.workspace(session()); assertEquals(2,w.path("selection").path("version").asInt()); assertEquals("s2",w.path("selection").path("sceneId").asText());
        assertEquals("s3",w.path("currentStepId").asText()); assertEquals("READY",w.path("steps").get(2).path("status").asText()); assertFalse(w.path("steps").get(2).has("artifactRef"));
        assertThrows(BusinessException.class,()->tx.execute(t->store.recordResult(session(),call("storyboard-generation"),new SkillResult("STORYBOARD","旧稿重试","错误",board.id(),data,new ArtifactRef(board.id(),1,"s2")))));
        var newScript=tx.execute(t->store.recordResult(session(),call("script-generation"),new SkillResult("SCRIPT","新脚本","新版脚本",script.id(),null,new ArtifactRef(script.id(),1,null))));
        assertEquals("s1",newScript.stepId());
        assertEquals("s2",store.workspace(session()).path("currentStepId").asText());
        assertEquals("READY",store.workspace(session()).path("steps").get(1).path("status").asText());
        assertEquals(2,store.workspace(session()).path("steps").get(0).path("artifactRef").path("version").asInt());
    }
    // 【测什么】取消后的已提交任务仍保留原来源，但不能推进新计划。
    // 【怎么算红】recordMedia丢弃冻结来源或改写停止后的workspace，断言失败。
    @Test void lateMediaKeepsSourceWithoutAdvancingNewWorkspace() {
        var p=plan(); apply("adopt",1,"ADOPT_PLAN",new ArtifactRef(p.id(),1,null));
        var script=store.artifact(session(),"source",null,"SCRIPT","来源","内容");
        apply("selection",2,"SELECT",new ArtifactRef(script.id(),1,null));
        var c=call("image-generation"); var turn=store.turn(c.turnId());
        String aid=approvals.create(session(),turn,c,new TaskQuote("p","m","模型","IMAGE",json.createObjectNode(),java.math.BigDecimal.ONE,"CNY"));
        approvals.bind(aid,"task-1"); store.status(turn.id(),"WAITING_TASK",null);
        apply("stop",3,"STOP_PLAN",null);
        tx.executeWithoutResult(t->approvals.artifact(session(),approvals.get(aid),"IMAGE","旧任务"));
        var media=store.artifacts(session()).stream().filter(a->"task-1".equals(a.taskId())).findFirst().orElseThrow();
        assertEquals(script.id(),media.sourceRef().path("artifactId").asText()); assertEquals(p.id(),media.planRef().path("artifactId").asText());
        assertFalse(store.workspace(session()).path("planConfirmed").asBoolean()); assertTrue(store.workspace(session()).path("steps").isEmpty());
    }
}
