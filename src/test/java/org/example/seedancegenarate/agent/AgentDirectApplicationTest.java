package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.generation.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.runtime.AgentGenerationRuntime;
import org.example.seedancegenarate.agent.skill.TaskQuote;
import org.example.seedancegenarate.agent.model.AgentContext.ArtifactRef;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.ConversationMediaResolver.LocalFiles;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real approval rows and rollback; gateway is fake and never contacts OSS or a generation provider. */
class AgentDirectApplicationTest {
    ObjectMapper json=new ObjectMapper(); JdbcTemplate db; TransactionTemplate tx; AgentStore store;
    AgentApprovalStore approvals; AgentDirectGenerationGateway gateway; AgentDirectApplication direct;
    AgentGenerationRuntime generation; AsyncJobService jobs; long id; TaskQuote quote;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:direct"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String file:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V40__agent_model_recovery.sql","V43__agent_turn_lifecycle.sql","V44__agent_output_repair.sql","V45__agent_scene_progress.sql","V47__agent_model_binding.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql","V54__agent_skill_output_repair.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        store=new AgentStore(db,json); approvals=new AgentApprovalStore(db,store); jobs=mock(AsyncJobService.class);
        gateway=mock(AgentDirectGenerationGateway.class);
        generation=new AgentGenerationRuntime(store,approvals,mock(AgentApprovalApplication.class),mock(AgentGenerationGateway.class),jobs,tx,json);
        direct=new AgentDirectApplication(store,tx,gateway,generation,json); id=store.create(1,"同一对话");
        quote=new TaskQuote("provider","model","图像模型","IMAGE",json.createObjectNode().put("model","model").put("prompt","雷达"),BigDecimal.ONE,"CNY","DIRECT");
        when(gateway.prepareDirect(anyLong(),anyString(),any(),any(),any())).thenReturn(quote);
    }
    AgentDirectApplication.Request request(String key) {
        return new AgentDirectApplication.Request(key,0L,"IMAGE",json.createObjectNode().put("model","model").put("prompt","雷达"),null,List.of());
    }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    // 【测什么】手动图片/视频入口也按初始prompt命名，重放报价不改标题、不创建额外任务。
    // 【怎么算红】只给Agent聊天入口加标题或重放再次报价，标题/调用次数断言失败。
    @Test void directGenerationNamesDefaultConversationFromInitialPrompt() {
        id=store.create(1,null);
        direct.submit(1,id,request("title"),LocalFiles.none());
        direct.submit(1,id,request("title"),LocalFiles.none());
        assertEquals("雷达",store.title(store.owned(id,1,false)));
        verify(gateway,times(1)).prepareDirect(anyLong(),anyString(),any(),any(),any());
        verifyNoInteractions(jobs);
    }
    // 【测什么】暂停/失败Recipe也不能被DIRECT抢占，保护原运行的恢复和停止身份。
    // 【怎么算红】删checkCurrent中的活动Recipe守卫，prepareDirect会被调用并生成审批。
    @Test void suspendedRecipeCannotBeDisplacedByDirectGeneration() {
        var s=store.owned(id,1,false);String turn=store.newTurn(s,"self","技能");store.status(turn,"SUSPENDED",null);
        db.update("INSERT INTO agent_recipe_run(id,session_id,recipe_version_id,turn_id,status,variables_json,results_json,approvals_json) VALUES('run',?,'version',?,'SUSPENDED','{}','{}','{}')",s.id(),turn);
        db.update("UPDATE agent_session SET active_recipe_run_id='run' WHERE id=?",s.id());
        assertEquals(409,assertThrows(BusinessException.class,()->direct.submit(1,id,request("direct"),LocalFiles.none())).getCode());
        db.update("UPDATE agent_recipe_run SET status='FAILED' WHERE id='run'");
        assertEquals(409,assertThrows(BusinessException.class,()->direct.submit(1,id,request("direct2"),LocalFiles.none())).getCode());
        assertEquals(turn,store.owned(id,1,false).activeTurnId());verifyNoInteractions(gateway);assertEquals(0,count("agent_approval"));
    }
    // 【测什么】直接生成不调用LLM/排生成任务，而是同一事务保存用户消息和待确认审批。
    // 【怎么算红】若submit不落审批或提前enqueue，审批计数/无作业断言失败。
    @Test void directRequestCreatesOnlyApprovalAndReplays() {
        direct.submit(1,id,request("one"),LocalFiles.none()); direct.submit(1,id,request("one"),LocalFiles.none());
        assertEquals(1,count("agent_approval")); assertEquals(1,count("agent_turn")); assertEquals(2,count("conversation_message"));
        assertEquals("WAITING_APPROVAL",store.turn(store.owned(id,1,false).activeTurnId()).status());
        verify(gateway,times(1)).prepareDirect(anyLong(),anyString(),any(),any(),any()); verifyNoInteractions(jobs);
    }
    // 【测什么】同名同大小但不同字节的文件不能复用同命令键；原文件重放不再次上传报价。
    // 【怎么算红】文件hash只用名称/大小或把重放放在上传后，冲突/调用次数断言失败。
    @Test void fileIdentityUsesBytesAndRetainsReplay() {
        var files=new LocalFiles(new MultipartFile[]{new MockMultipartFile("images","same.png","image/png",new byte[]{1,2})},List.of("file"),null,null);
        direct.submit(1,id,request("file"),files); direct.submit(1,id,request("file"),files);
        var changed=new LocalFiles(new MultipartFile[]{new MockMultipartFile("images","same.png","image/png",new byte[]{2,1})},List.of("file"),null,null);
        assertThrows(BusinessException.class,()->direct.submit(1,id,request("file"),changed));
        verify(gateway,times(1)).prepareDirect(anyLong(),anyString(),any(),any(),any()); assertEquals(1,count("agent_approval"));
    }
    // 【测什么】越权对话、等待用户的轮次、过时workspace均在上传前拒绝。
    // 【怎么算红】删除初次owner/busy/version校验，Gateway被调用而never断言失败。
    @Test void ownershipBusyAndVersionAreCheckedBeforeUpload() {
        assertThrows(BusinessException.class,()->direct.submit(2,id,request("foreign"),LocalFiles.none()));
        var stale=new AgentDirectApplication.Request("stale",1L,"IMAGE",request("x").input(),null,List.of());
        assertThrows(BusinessException.class,()->direct.submit(1,id,stale,LocalFiles.none()));
        String turn=store.newTurn(store.owned(id,1,false),"self","问题"); store.status(turn,"WAITING_USER",null);
        assertThrows(BusinessException.class,()->direct.submit(1,id,request("busy"),LocalFiles.none()));
        verifyNoInteractions(gateway); assertEquals(0,count("agent_approval"));
    }
    // 【测什么】上传/报价在事务外，其间工作区改变后不得创建旧方案审批。
    // 【怎么算红】删最终session锁下version检查或在事务中prepare，断言失败。
    @Test void changedWorkspaceDuringPreparationDoesNotCreateAnApproval() {
        when(gateway.prepareDirect(anyLong(),anyString(),any(),any(),any())).thenAnswer(a->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var s=store.owned(id,1,false); var w=store.workspace(s); w.put("version",1); store.saveWorkspace(s,w); return quote;
        });
        assertThrows(BusinessException.class,()->direct.submit(1,id,request("race"),LocalFiles.none()));
        assertEquals(0,count("agent_turn")); assertEquals(0,count("agent_approval")); assertEquals(0,count("conversation_message"));
    }
    // 【测什么】审批回写失败时用户消息、Turn、Call和确认单全部回滚。
    // 【怎么算红】删除Direct事务边界，写后异常会残留计数大于0。
    @Test void approvalFailureRollsBackAllDurableAcceptance() {
        var broken=spy(generation);
        doAnswer(a->{a.callRealMethod();throw new IllegalStateException("write outcome failure");}).when(broken).awaitApproval(any(),any(),any(),any());
        var failing=new AgentDirectApplication(store,tx,gateway,broken,json);
        assertThrows(IllegalStateException.class,()->failing.submit(1,id,request("rollback"),LocalFiles.none()));
        for(String table:List.of("agent_turn","agent_skill_call","agent_approval","conversation_message")) assertEquals(0,count(table),table);
        assertNull(store.owned(id,1,false).activeTurnId());
    }
    // 【测什么】超限文件在读取内容/上传前拒绝，防幂等摘要过程变成无界文件读取。
    // 【怎么算红】去掉大小/数量前置守卫，getInputStream或Gateway被调用而断言失败。
    @Test void oversizedFileIsRejectedBeforeReadingItsBody() throws Exception {
        var file=mock(MultipartFile.class); when(file.getSize()).thenReturn(51L*1024*1024); when(file.isEmpty()).thenReturn(false);
        assertThrows(BusinessException.class,()->direct.submit(1,id,request("large"),new LocalFiles(new MultipartFile[]{file},List.of("file"),null,null)));
        verify(file,never()).getInputStream(); verifyNoInteractions(gateway);
    }
    // 【测什么】来源幕准确保存，不能借分镜ID引用不存在的幕；直接调用不携带计划步骤。
    // 【怎么算红】删scene检查或丢sourceRef/保留planRef，引用和context断言失败。
    @Test void sourceSceneIsValidatedAndFrozenWithoutPlanSteps() {
        var s=store.owned(id,1,false); var a=store.artifact(s,"board",null,"STORYBOARD","分镜","雷达");
        db.update("UPDATE agent_artifact_version SET data_json=?",store.write(Map.of("scenes",List.of(Map.of("sceneId","s2","title","雷达")))));
        var invalid=new AgentDirectApplication.Request("bad",0L,"IMAGE",request("x").input(),new ArtifactRef(a.id(),1,"other"),List.of());
        assertThrows(BusinessException.class,()->direct.submit(1,id,invalid,LocalFiles.none()));
        var valid=new AgentDirectApplication.Request("good",0L,"IMAGE",request("x").input(),new ArtifactRef(a.id(),1,"s2"),List.of());
        direct.submit(1,id,valid,LocalFiles.none());
        String call=db.queryForObject("SELECT call_id FROM agent_approval",String.class); var context=store.callContext(call);
        assertEquals("s2",context.path("sourceRef").path("sceneId").asText()); assertEquals(a.id(),context.path("sourceRef").path("artifactId").asText());
        assertEquals("DIRECT",context.path("executionMode").asText()); assertTrue(context.path("planRef").isNull()); assertTrue(context.path("steps").isEmpty());
    }
}
