package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.api.AgentDebugController;
import org.example.seedancegenarate.agent.application.AgentDebugQuery;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentDebugQueryTest {
    JdbcTemplate db; AgentDebugQuery query; MockMvc mvc;
    final List<String> reads=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:debug"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds) {
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
                reads.add(sql);
                return super.query(sql.replace(" COLLATE utf8mb4_unicode_ci",""),mapper,args);
            }
        };
        db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String file:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V40__agent_model_recovery.sql","V43__agent_turn_lifecycle.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql","V54__agent_skill_output_repair.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        db.execute("CREATE TABLE video_task(id BIGINT PRIMARY KEY,user_id BIGINT,task_id VARCHAR(128),status VARCHAR(32),output_type VARCHAR(16),provider VARCHAR(32),update_time DATETIME,prompt TEXT,error_msg TEXT)");
        db.update("INSERT INTO conversation(id,user_id,title,archived,creation_mode) VALUES(1,7,'SECRET_TITLE',0,'AGENT'),(2,8,'OTHER_USER',0,'AGENT')");
        db.update("INSERT INTO agent_session(id,conversation_id,user_id,goal,summary) VALUES('s1',1,7,'SECRET_GOAL','SECRET_SUMMARY'),('s2',2,8,'OTHER_GOAL','OTHER_SUMMARY')");
        query=new AgentDebugQuery(db); mvc=MockMvcBuilders.standaloneSetup(new AgentDebugController(query)).build();
        UserContext.clear(); reads.clear();
    }
    @AfterEach void clear() { UserContext.clear(); }
    void admin() { var user=new AppUser(); user.setId(3L); user.setRole("ADMIN"); UserContext.setUser(user); }
    void turn(String id,String session) {
        db.update("INSERT INTO agent_turn(id,session_id,channel,status,step_no,epoch,error_message,deadline_at) VALUES(?,?,'local','WAITING_USER',2,3,'SECRET_ERROR','2000-01-01')",id,session);
    }

    // 【测什么】真实管理员GET返回诊断元信息，而不要求拥有用户的会话。
    // 【怎么算红】查询尚未实现或误用owned(adminId)时，HTTP200与session断言失败。
    @Test void adminReadsExistingForeignConversation() throws Exception {
        turn("t","s1"); db.update("UPDATE agent_turn SET turn_seq=3 WHERE id='t'");
        admin(); mvc.perform(get("/api/admin/agent/conversations/1/diagnostics"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.id").value("s1"))
                .andExpect(jsonPath("$.data.conversationId").value("1"))
                .andExpect(jsonPath("$.data.turns.items[0].turnSeq").value(3));
    }

    // 【测什么】未登录和普通用户在任何SQL之前拒绝，即使参数非法也不泄露会话。
    // 【怎么算红】删除Controller管理员检查，状态或零SQL断言失败。
    @Test void noSqlForNonAdministrators() throws Exception {
        mvc.perform(get("/api/admin/agent/conversations/1/diagnostics")).andExpect(status().isUnauthorized());
        var user=new AppUser(); user.setId(7L); user.setRole("USER"); UserContext.setUser(user);
        mvc.perform(get("/api/admin/agent/conversations/1/diagnostics")).andExpect(status().isForbidden());
        assertTrue(reads.isEmpty());
    }

    // 【测什么】非法、超Long和超界limit按4xx拒绝，归档/非Agent/缺失会话404。
    // 【怎么算红】移除ID/limit校验或归档过滤，对应状态断言失败。
    @Test void invalidAndMissingAreClientErrors() throws Exception {
        admin();
        for(String id:List.of("0","-1","abc","9223372036854775808","1e2")) mvc.perform(get("/api/admin/agent/conversations/"+id+"/diagnostics")).andExpect(status().isBadRequest());
        for(String limit:List.of("0","51","-1","abc","99999999999999999999")) mvc.perform(get("/api/admin/agent/conversations/1/diagnostics").param("limit",limit)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/agent/conversations/9/diagnostics")).andExpect(status().isNotFound());
        db.update("UPDATE conversation SET archived=1 WHERE id=1");
        mvc.perform(get("/api/admin/agent/conversations/1/diagnostics")).andExpect(status().isNotFound());
        db.update("UPDATE conversation SET archived=0,creation_mode='LEGACY' WHERE id=1");
        mvc.perform(get("/api/admin/agent/conversations/1/diagnostics")).andExpect(status().isNotFound());
    }

    void trace(String suffix,String session) {
        String tid="turn"+suffix; turn(tid,session);
        db.update("INSERT INTO agent_decision(id,turn_id,epoch,step_no,payload) VALUES(?,?,3,2,'SECRET_DECISION')","decision"+suffix,tid);
        db.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no,error_message) VALUES(?,?,'script-generation','1','SECRET_INPUT','SUCCEEDED',3,2,'SECRET_CALL_ERROR')","call"+suffix,tid);
        db.update("INSERT INTO agent_plan(id,session_id,artifact_id,artifact_version,turn_id,execution_epoch,status,reason) VALUES(?,?,?,1,?,3,'RUNNING','SECRET_PLAN_REASON')","plan"+suffix,session,"artifact"+suffix,tid);
        db.update("INSERT INTO agent_plan_step(id,plan_id,step_key,ordinal_no,kind,title,depends_on,status,skill_call_id,input_json,error_code) VALUES(?,?,'s1',1,'SCRIPT','SECRET_STEP_TITLE','[]','FAILED',?,'SECRET_STEP_INPUT','MODEL_TIMEOUT')","step"+suffix,"plan"+suffix,"call"+suffix);
        db.update("INSERT INTO agent_model_recovery(turn_id,execution_epoch,step_no,phase,attempt_count,expected_job_key,workspace_version,recipe_json,status,error_code) VALUES(?,3,2,'DECISION',2,'SECRET_JOB_KEY',1,'SECRET_RECIPE','WAITING_RETRY','MODEL_TIMEOUT')",tid);
    }
    void approval(String id,String tid,String task) {
        db.update("INSERT INTO agent_approval(id,session_id,turn_id,call_id,epoch,step_no,status,quote_json,request_id,task_id,error_message,expires_at) VALUES(?,'s1',?,?,3,2,'APPROVED','SECRET_QUOTE',?,?,'SECRET_APPROVAL_ERROR','2000-01-01')",id,tid,"approvalcall"+id,"request"+id,task);
    }
    // 【测什么】各集合有界且会话隔离，所有正文不投影也不SELECT；重复诊断不修改已过期交互。
    // 【怎么算红】删除LIMIT/会话过滤/白名单或调用有副作用Snapshot，长度/隐私/表快照断言失败。
    @Test void boundedMetadataReadHasNoPayloadOrSideEffects() throws Exception {
        trace("a","s1"); trace("b","s1"); trace("other","s2");
        db.update("UPDATE agent_session SET active_turn_id='turnb' WHERE id='s1'");
        db.update("INSERT INTO agent_interaction(id,turn_id,epoch,status,question,options_json,expires_at) VALUES('expired','turna',3,'PENDING','SECRET_QUESTION','[]','2000-01-01')");
        var before=state(); reads.clear();
        var result=query.read(1,1); query.read(1,1);
        assertEquals(before,state()); assertTrue(result.turns().truncated()); assertTrue(result.decisions().truncated());
        assertTrue(result.plans().truncated()); assertTrue(result.steps().truncated()); assertTrue(result.calls().truncated()); assertTrue(result.recoveries().truncated());
        assertEquals(1,result.turns().items().size()); assertEquals(1,result.steps().items().size());
        String body=new ObjectMapper().writeValueAsString(result);
        assertFalse(body.contains("SECRET")); assertFalse(body.contains("other")); assertTrue(body.contains("MODEL_TIMEOUT"));
        assertTrue(reads.stream().allMatch(sql->sql.startsWith("SELECT ") && !sql.contains("FOR UPDATE")));
        assertTrue(reads.stream().filter(sql->!sql.contains("FROM agent_session")).allMatch(sql->sql.endsWith("LIMIT ?")));
        String projection=String.join("\n",reads).toLowerCase(Locale.ROOT);
        for(String forbidden:List.of("payload","input_json","context_json","quote_json","error_message","goal","summary","recipe_json")) assertFalse(projection.contains(forbidden),forbidden);
        assertFalse(query.read(1,50).turns().truncated());
    }

    // 【测什么】Task状态来自真实task而非Approval，未提交和跨用户Task不泄露状态/Provider。
    // 【怎么算红】删除v.user_id条件或用a.status冒充task_status，RUNNING/null断言失败。
    @Test void taskStatusIsRealAndOwnerChecked() {
        turn("t","s1"); approval("a","t","mine"); approval("b","t",null); approval("c","t","foreign");
        db.update("INSERT INTO video_task(id,user_id,task_id,status,output_type,provider,prompt,error_msg) VALUES(1,7,'mine','RUNNING','VIDEO','comfyui','SECRET_TASK','SECRET_ERROR'),(2,8,'foreign','SUCCEEDED','IMAGE','PRIVATE_PROVIDER','SECRET_TASK','SECRET_ERROR')");
        var result=query.read(1,50);
        var mine=result.tasks().items().stream().filter(t->"a".equals(t.approvalId())).findFirst().orElseThrow();
        assertEquals("RUNNING",mine.status()); assertEquals("APPROVED",mine.approvalStatus());
        for(var item:result.tasks().items()) if(!"a".equals(item.approvalId())) {
            assertNull(item.status()); assertNull(item.provider()); assertNull(item.outputType());
        }
        assertTrue(query.read(1,1).tasks().truncated());
    }

    // 【测什么】不符合安全代码格式的数据不作为错误正文返回，缺省关联和epoch可为空。
    // 【怎么算红】去除错误码白名单，SECRET prose会进入JSON。
    @Test void unexpectedErrorTextIsNotDisclosed() throws Exception {
        trace("a","s1");
        db.update("UPDATE agent_plan_step SET error_code='SECRET prose' WHERE id='stepa'");
        db.update("UPDATE agent_model_recovery SET error_code='SECRET prose'");
        db.update("UPDATE agent_plan SET execution_epoch=NULL,turn_id=NULL");
        var result=query.read(1,20);
        assertEquals("UNKNOWN",result.steps().items().get(0).errorCode());
        assertEquals("UNKNOWN",result.recoveries().items().get(0).errorCode());
        assertNull(result.plans().items().get(0).executionEpoch());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("SECRET"));
    }
    // 【测什么】数据库失败只返回固定诊断错误，不把SQL异常详情送到管理员UI。
    // 【怎么算红】移除DataAccessException本地处理，MockMvc将抛SQL错误而非固定500。
    @Test void databaseFailureHasSafeResponse() throws Exception {
        admin(); db.execute("DROP TABLE agent_turn");
        mvc.perform(get("/api/admin/agent/conversations/1/diagnostics"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("诊断读取失败，请稍后重试"));
    }
    Map<String,Object> state() {
        var result=new TreeMap<String,Object>();
        for(String table:List.of("conversation","agent_session","agent_turn","agent_decision","agent_plan","agent_plan_step","agent_skill_call","agent_interaction","agent_approval","agent_model_recovery"))
            result.put(table,db.queryForList("SELECT * FROM "+table));
        return result;
    }
}
