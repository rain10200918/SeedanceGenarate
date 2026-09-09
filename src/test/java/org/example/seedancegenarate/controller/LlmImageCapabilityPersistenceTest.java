package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.example.seedancegenarate.config.AgentModelCallConfig;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.mapper.LlmChannelMapper;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.example.seedancegenarate.service.llm.LlmChannelRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LlmImageCapabilityPersistenceTest {
    // 【测什么】真实MyBatis写入/读取图片开关及V46 NULL迁移，旧名单不能覆盖保存后的false。
    // 【怎么算红】去掉entity字段/管理SET/迁移列或resolvedSpec显式值优先，SQL或能力断言失败。
    @Test void adminSaveAndStrictRoutingShareDatabaseTruth() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:image-channel-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE llm_channel(name VARCHAR(64) PRIMARY KEY,base_url VARCHAR(255),api_key VARCHAR(255),model VARCHAR(128),temperature DECIMAL(5,2),max_tokens INT,token_param VARCHAR(32),timeout_ms INT,priority INT,enabled BOOLEAN,archived BOOLEAN,remark VARCHAR(255),create_time TIMESTAMP,update_time TIMESTAMP)");
        db.update("INSERT INTO llm_channel(name,model,base_url,enabled,archived) VALUES('legacy','m','http://h/v1',true,false)");
        db.execute(Files.readString(Path.of("src/main/resources/db/migration/V46__llm_image_capability.sql")));
        assertNull(db.queryForObject("SELECT supports_images FROM llm_channel WHERE name='legacy'",Boolean.class));
        var configuration=new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test",new JdbcTransactionFactory(),ds));
        configuration.addMapper(LlmChannelMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
        var admin=new AppUser();admin.setId(1L);admin.setRole("admin");UserContext.setUser(admin);
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(LlmChannelMapper.class);
            var calls=new AgentModelCallConfig();calls.setImageChannels(List.of("legacy"));
            var registry=new LlmChannelRegistry(mapper,new PromptOptimizeConfig(),calls);
            var controller=new AdminLlmChannelController(mapper,registry,mock(PromptOptimizeService.class));
            assertTrue(registry.findRoutableStrict("legacy").supportsImages());
            var patch=new AdminLlmChannelController.LlmChannelUpsertRequest();patch.setSupportsImages(false);
            controller.update("legacy",patch);session.clearCache();
            assertEquals(Boolean.FALSE,db.queryForObject("SELECT supports_images FROM llm_channel WHERE name='legacy'",Boolean.class));
            assertFalse(registry.findRoutableStrict("legacy").supportsImages());
            patch.setSupportsImages(true);controller.update("legacy",patch);session.clearCache();
            assertTrue(registry.findRoutableStrict("legacy").supportsImages());
            patch.setSupportsImages(null);patch.setModel("text-model");controller.update("legacy",patch);session.clearCache();
            assertFalse(registry.findRoutableStrict("legacy").supportsImages());
            var create=new AdminLlmChannelController.LlmChannelUpsertRequest();
            create.setName("new-vision");create.setModel("vision");create.setBaseUrl("http://v/v1");create.setApiKey("test-key");create.setSupportsImages(true);
            controller.create(create);session.clearCache();
            assertTrue(mapper.selectById("new-vision").getSupportsImages());
            assertFalse(mapper.selectById("new-vision").getEnabled());
        } finally {UserContext.clear();}
    }
}
