package org.example.seedancegenarate.agent;

import org.example.seedancegenarate.agent.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentMessageBatchProjectionTest {
    final AgentWorkspaceIntegrationTest f=new AgentWorkspaceIntegrationTest();
    final AtomicInteger queries=new AtomicInteger();
    final java.util.List<String> sqls=new java.util.ArrayList<>();
    String failOn;
    AgentStore store; AgentRows.Session session; String turn;
    @BeforeEach void setup() throws Exception {
        f.setup(); session=f.session(); turn=f.store.newTurn(session,"local","batch projection");
        var counted=new DelegatingDataSource(f.db.getDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
                        String sql=((String)args[0]).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+"," ");
                        queries.incrementAndGet(); sqls.add(sql);
                        if(failOn!=null && sql.contains(failOn)) throw new SQLTransientConnectionException("projection unavailable","08006");
                    }
                    try { return method.invoke(connection,args); }
                    catch(InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        store=new AgentStore(new JdbcTemplate(counted),f.json);
    }
    void add(int n) {
        f.db.update("INSERT INTO agent_skill_call(id,turn_id,skill_id,skill_version,input_json,status,epoch,step_no) VALUES(?,?,'video-generation','1','{}','READY',1,?)","c"+n,turn,n);
        f.db.update("INSERT INTO agent_interaction(id,turn_id,epoch,status,question,options_json,expires_at) VALUES(?,?,1,'ANSWERED','q','[]',TIMESTAMPADD(DAY,1,NOW()))","i"+n,turn);
        f.db.update("INSERT INTO agent_video_prompt_checkpoint(call_id,scene_key,session_id,turn_id,execution_epoch,binding_hash,ordinal) VALUES(?,'scene',?,?,1,?,1)","c"+n,session.id(),turn,"a".repeat(64));
        var parts=f.json.createArrayNode();
        parts.addObject().put("type","choice").put("interactionId","i"+n).put("status","PENDING");
        parts.addObject().put("type","skill_call").put("skillCallId","c"+n).put("status","old");
        parts.add(parts.get(0).deepCopy()); parts.add("literal");
        f.store.message(session,turn,"ASSISTANT","message "+n,parts,"request"+n,null);
    }
    // 【测什么】1与100条不同关联的消息查询数有常数上限，最新100条顺序、完整parts和消息元数据兼容。
    // 【怎么算红】恢复逐part查询，100条需401次SELECT而非3次且不等于1条；改排序/字段/phase也会红。
    @Test void hundredMessagesUseBoundedQueriesAndKeepProjection() {
        add(0); queries.set(0); store.messages(session); int one=queries.get();
        for(int n=1;n<=100;n++) add(n);
        queries.set(0); var messages=store.messages(session);
        int hundred=queries.get(); assertEquals(100,messages.size());
        for(int n=1;n<=100;n++) {
            var actual=messages.get(n-1); var expected=f.json.createArrayNode();
            expected.addObject().put("type","choice").put("interactionId","i"+n).put("status","ANSWERED").put("version",1);
            expected.addObject().put("type","skill_call").put("skillCallId","c"+n).put("status","READY").put("phase","VIDEO_PROMPT_PREPARATION");
            expected.add(expected.get(0).deepCopy()); expected.add("literal");
            assertEquals(expected,actual.parts()); assertEquals(n+1,actual.seq());
            assertEquals("request"+n,actual.clientMsgId()); assertEquals("ASSISTANT",actual.role());
            assertNotNull(actual.id()); assertNotNull(actual.createdAt());
        }
        assertEquals(3,one,"single-message SELECT count");
        assertEquals(3,hundred,"100-message SELECT count");
    }
    // 【测什么】每次重读状态与审批；缺失关联、非数组和其他属主/会话消息不改变既有投影边界。
    // 【怎么算红】跨请求缓存、漏approval排除或消息user/conversation过滤、吞缺失part会使对应断言红。
    @Test void updatesMissingPartsAndMessageScopeRemainCompatible() throws Exception {
        assertTrue(store.messages(session).isEmpty()); add(0); store.messages(session);
        f.db.update("UPDATE agent_interaction SET version=2,status='EXPIRED' WHERE id='i0'");
        f.db.update("UPDATE agent_skill_call SET status='COMPLETED' WHERE id='c0'");
        f.db.update("INSERT INTO agent_approval(id,session_id,turn_id,call_id,epoch,step_no,status,quote_json,request_id,expires_at) VALUES('a',?,?,'c0',1,0,'CANCELLED','{}','a',NOW())",session.id(),turn);
        var parts=store.messages(session).get(0).parts();
        assertEquals("EXPIRED",parts.get(0).path("status").asText()); assertEquals(2,parts.get(0).path("version").asInt());
        assertEquals("COMPLETED",parts.get(1).path("status").asText()); assertFalse(parts.get(1).has("phase"));
        var missing=f.json.readTree("[{\"type\":\"choice\",\"interactionId\":\"missing\",\"status\":\"saved\"},{\"type\":\"skill_call\",\"skillCallId\":\"missing\",\"phase\":\"saved\"},null,7,{}]");
        f.store.message(session,turn,"ASSISTANT",null,missing,"missing",null);
        f.store.message(session,turn,"ASSISTANT",null,f.json.createObjectNode(),"object",null);
        var foreign=new AgentRows.Session(session.id(),session.conversationId(),2,0,null,null,null);
        f.store.message(foreign,turn,"ASSISTANT",null,missing,"foreign",null);
        var other=f.store.owned(f.store.create(1,"other"),1,false);
        f.store.message(other,null,"ASSISTANT",null,missing,"other",null);
        var result=store.messages(session); assertEquals(3,result.size());
        assertEquals(missing,result.get(1).parts()); assertEquals(f.json.createArrayNode(),result.get(2).parts());
        assertEquals(result,store.messages(session));
    }
    // 【测什么】真实投影SQL无FOR UPDATE，执行路径call(id)仍保留该锁。
    // 【怎么算红】投影重用call(id)或新增锁SQL、删除执行call的锁，将分别触发否定/肯定断言。
    @Test void projectionDoesNotLockButExecutionCallStillDoes() {
        add(0); store.messages(session);
        assertEquals(3,sqls.size());
        assertTrue(sqls.stream().noneMatch(sql->sql.contains("for update")),sqls.toString());
        sqls.clear(); assertNotNull(store.call("c0"));
        assertTrue(sqls.stream().anyMatch(sql->sql.contains("for update")),sqls.toString());
    }
    // 【测什么】消息、choice、call及准备状态查询失败都向上传播，恢复后重试重新读全量且不写回parts。
    // 【怎么算红】捕获DB错误返回空/部分/缓存投影，异常断言失败；缓存失败则恢复重试失败。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"conversation_message","agent_interaction","agent_skill_call","agent_video_prompt_checkpoint"})
    void projectionQueryFailurePropagatesAndRetryIsFresh(String table) {
        add(0); var expected=store.messages(session); sqls.clear(); failOn=table;
        var error=assertThrows(org.springframework.dao.DataAccessException.class,()->store.messages(session));
        assertEquals("projection unavailable",error.getMostSpecificCause().getMessage());
        assertTrue(sqls.stream().anyMatch(sql->sql.contains(table)));
        failOn=null; assertEquals(expected,store.messages(session));
        assertFalse(f.db.queryForObject("SELECT parts FROM conversation_message WHERE client_msg_id='request0'",String.class).contains("VIDEO_PROMPT_PREPARATION"));
    }
    // 【测什么】非视频call即使有checkpoint、视频call缺checkpoint，都不能新增准备phase。
    // 【怎么算红】移除skill_id条件或EXISTS条件，对应输入将错误新增phase而红。
    @Test void preparationRequiresVideoSkillAndCheckpoint() {
        add(0); f.db.update("UPDATE agent_skill_call SET skill_id='image-generation' WHERE id='c0'");
        assertFalse(store.messages(session).get(0).parts().get(1).has("phase"));
        f.db.update("UPDATE agent_skill_call SET skill_id='video-generation' WHERE id='c0'");
        f.db.update("DELETE FROM agent_video_prompt_checkpoint WHERE call_id='c0'");
        assertFalse(store.messages(session).get(0).parts().get(1).has("phase"));
    }
}
