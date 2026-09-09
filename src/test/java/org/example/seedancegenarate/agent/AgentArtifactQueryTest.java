package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.application.AgentArtifactQueryApplication;
import org.example.seedancegenarate.agent.persistence.AgentStore;
import org.example.seedancegenarate.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentArtifactQueryTest {
    JdbcTemplate db; AgentStore store; AgentArtifactQueryApplication query; long conversation;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:artifact-query"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds); db.execute("CREATE TABLE prompt_token_usage(id BIGINT)");
        for(String file:List.of("V33__conversation.sql","V34__agent_runtime.sql","V35__agent_approval.sql","V36__agent_workspace.sql","V37__agent_persistent_plan.sql","V38__creative_recipe.sql","V39__agent_recipe_run.sql","V49__agent_generation_batch.sql","V52__agent_video_prompt_checkpoint.sql")) {
            String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file)).readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");").replace("ALTER TABLE agent_skill_call DROP INDEX uk_agent_call_step","ALTER TABLE agent_skill_call DROP CONSTRAINT uk_agent_call_step");
            new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        }
        store=new AgentStore(db,new ObjectMapper()); query=new AgentArtifactQueryApplication(store); conversation=store.create(1,"作品");
    }
    String add(long owner,long cid,String id) {
        return store.artifact(store.owned(cid,owner,false),UUID.randomUUID().toString(),id,"SCRIPT","脚本","正文").id();
    }
    void code(int code,Runnable run) { assertEquals(code,assertThrows(BusinessException.class,run::run).getCode()); }
    // 【测什么】版本行keyset有界且新插入版本不污染已开始的翻页，响应丢失重读同cursor一致。
    // 【怎么算红】去掉id<cursor或limit+1哨兵，重复版本/漏页/终页cursor断言失败。
    @Test void stableCursorAndExactBoundary() {
        String id=add(1,conversation,null); for(int i=0;i<4;i++) add(1,conversation,id);
        var page=query.page(1,conversation,null,2,null);
        assertEquals(List.of(5,4),page.items().stream().map(a->a.version()).toList()); assertNotNull(page.nextCursor());
        add(1,conversation,id);
        var next=query.page(1,conversation,page.nextCursor(),2,null);
        assertEquals(List.of(3,2),next.items().stream().map(a->a.version()).toList());
        assertEquals(next,query.page(1,conversation,page.nextCursor(),2,null));
        var end=query.page(1,conversation,next.nextCursor(),2,null); assertEquals(1,end.items().size()); assertNull(end.nextCursor());
        assertNull(query.page(1,conversation,null,6,null).nextCursor());
        assertEquals(6,query.page(1,conversation,null,1,null).items().get(0).version());
    }
    // 【测什么】会话、用户、作品及软删除隔离，坏归属行也不泄漏。
    // 【怎么算红】移除owned或SQL的session/user/作品条件，越权/过滤断言失败。
    @Test void ownershipFilterAndDeletedConversation() {
        String mine=add(1,conversation,null); String second=add(1,conversation,null);
        long other=store.create(2,"他人"); String foreign=add(2,other,null);
        long sameOwnerOther=store.create(1,"另一个"); String elsewhere=add(1,sameOwnerOther,null);
        assertEquals(List.of(mine),query.page(1,conversation,null,20,mine).items().stream().map(a->a.id()).toList());
        assertTrue(query.page(1,conversation,null,20,"missing").items().isEmpty());
        code(404,()->query.page(2,conversation,null,20,null)); code(404,()->query.version(1,conversation,foreign,1));
        code(404,()->query.version(1,conversation,elsewhere,1)); code(404,()->query.version(1,conversation,mine,2));
        db.update("UPDATE agent_artifact_version SET user_id=2 WHERE artifact_id=?",second);
        assertEquals(1,query.page(1,conversation,null,20,null).items().size()); code(404,()->query.version(1,conversation,second,1));
        db.update("UPDATE conversation SET archived=1 WHERE id=?",conversation);
        code(404,()->query.page(1,conversation,null,20,null)); code(404,()->query.version(1,conversation,mine,1));
    }
    // 【测什么】空/负数/溢出/非十进制/超长参数均400，不抛解析500或无界查询。
    // 【怎么算红】删除任一输入校验，至少一组坏值不再400。
    @Test void malformedInputIs400() {
        for(String cursor:List.of("","0","-1","+1"," 1","1.0","1 OR 1=1","9223372036854775808","1".repeat(100)))
            code(400,()->query.page(1,conversation,cursor,20,null));
        for(int limit:List.of(0,-1,51,Integer.MAX_VALUE)) code(400,()->query.page(1,conversation,null,limit,null));
        for(String id:List.of(""," ","x".repeat(65))) {code(400,()->query.page(1,conversation,null,20,id));code(400,()->query.version(1,conversation,id,1));}
        code(400,()->query.version(1,conversation,null,1)); code(400,()->query.version(1,conversation,"x",0));
        assertTrue(query.page(1,conversation,"9223372036854775807",50,null).items().isEmpty());
    }
    // 【测什么】只读浏览在重试/待执行会话中不改变所有已存在数据。
    // 【怎么算红】调用snapshot或touch/SELECT/命令写，前后表快照不相等。
    @Test void queriesDoNotMutateAnyState() {
        String id=add(1,conversation,null);
        String session=store.owned(conversation,1,false).id();
        db.update("INSERT INTO agent_turn(id,session_id,channel,status,deadline_at) VALUES('waiting',?,'local','WAITING_USER','2000-01-01')",session);
        db.update("UPDATE agent_session SET active_turn_id='waiting' WHERE id=?",session);
        db.update("INSERT INTO agent_interaction(id,turn_id,epoch,status,question,options_json,expires_at) VALUES('expired','waiting',1,'PENDING','问题','[]','2000-01-01')");
        var before=database(); assertEquals(id,query.version(1,conversation,id,1).id());
        query.page(1,conversation,null,20,null); query.version(1,conversation,id,1);
        assertEquals(before,database());
    }
    Map<String,Object> database() {
        var result=new TreeMap<String,Object>();
        for(String table:List.of("conversation","conversation_message","agent_session","agent_turn","agent_interaction","agent_approval","agent_artifact_version","agent_plan","agent_plan_step","agent_skill_call"))
            result.put(table,db.queryForList("SELECT * FROM "+table));
        return result;
    }
}
