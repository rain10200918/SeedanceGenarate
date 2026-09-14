package org.example.seedancegenarate.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.example.seedancegenarate.agent.application.*;
import org.example.seedancegenarate.agent.persistence.*;
import org.example.seedancegenarate.agent.generation.AgentGenerationGateway;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.AssetService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSnapshotSqlBoundaryTest {
    // 【测什么】真实JdbcTemplate和MyBatis图片查询共享同一个RR连接，整个正常快照不发DML或FOR UPDATE。
    // 【怎么算红】恢复消息call锁读、去掉事务隔离或Mapper脱离事务，会触发SQL/连接/隔离断言。
    @Test void realJdbcAndMybatisShareReadViewWithoutLockingAndRefreshRevokedImages() throws Exception {
        var f=new AgentMessageBatchProjectionTest();f.setup();f.add(0);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource(
                "db/migration/V53__agent_video_prompt_repair.sql")).execute(f.f.db.getDataSource());
        f.f.db.execute("CREATE TABLE user_asset(id BIGINT PRIMARY KEY,user_id BIGINT,type VARCHAR(16),source VARCHAR(16),url VARCHAR(255),task_id VARCHAR(64),folder_id BIGINT,status VARCHAR(16),create_time TIMESTAMP)");
        f.f.db.update("INSERT INTO user_asset(id,user_id,type,url,status) VALUES(1,1,'IMAGE','https://media.example.test/one.png','ACTIVE')");
        var parts=f.f.json.createArrayNode();parts.addObject().put("type","image").put("assetId","1");
        parts.addObject().put("type","approval").put("approvalId","missing");
        f.f.store.message(f.session,f.turn,"USER",null,parts,null,null);
        Set<Connection> projectionConnections=Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> sqls=new ArrayList<>();
        var ds=new DelegatingDataSource(f.f.db.getDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                    if(m.getName().equals("prepareStatement")) {
                        String sql=((String)args[0]).toUpperCase(Locale.ROOT);sqls.add(sql);
                        assertTrue(sql.startsWith("SELECT"),sql);assertFalse(sql.contains("FOR UPDATE"),sql);
                        if(TransactionSynchronizationManager.isActualTransactionActive()) {
                            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                            assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
                            projectionConnections.add(connection);
                        }
                    }
                    try {return m.invoke(connection,args);}catch(InvocationTargetException e){throw e.getCause();}
                });
            }
        };
        var db=new JdbcTemplate(ds);var store=new AgentStore(db,f.f.json);var approvals=new AgentApprovalStore(db,store);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var config=new MybatisConfiguration();config.addMapper(UserAssetMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var mapper=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(UserAssetMapper.class);
        var oss=new OssConfig();oss.setDomain("https://media.example.test");
        var app=new AgentApplication(store,tx,f.f.jobs,f.f.models,f.f.json,
                new AgentApprovalApplication(store,approvals,tx,f.f.jobs,mock(AgentGenerationGateway.class),f.f.json),approvals,
                new AgentImageInputs(mock(AssetService.class),mapper,oss));
        var first=app.snapshot(1,f.f.id);assertEquals(1,projectionConnections.size());
        assertTrue(sqls.stream().anyMatch(sql->sql.contains("USER_ASSET")));
        assertTrue(first.messages().get(1).parts().get(0).has("url"));
        f.f.db.update("UPDATE user_asset SET status='DELETED' WHERE id=1");
        projectionConnections.clear();
        var next=app.snapshot(1,f.f.id);assertEquals(1,projectionConnections.size());
        assertEquals(first.revision(),next.revision());assertFalse(next.messages().get(1).parts().get(0).has("url"));
        assertEquals("作品不可用",next.messages().get(1).parts().get(1).path("text").asText());
    }
}
