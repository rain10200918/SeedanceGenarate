package org.example.seedancegenarate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.agent.model.AgentModelGateway;
import org.example.seedancegenarate.agent.recipe.*;
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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipeTemplatesTest {
    JdbcTemplate db;RecipeCatalog catalog;AgentModelGateway model;RecipeCompiler compiler;
    @BeforeEach void setup() throws Exception {
        var json=new ObjectMapper();var ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:templates"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(ds);
        String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V38__creative_recipe.sql")).readAllBytes(),StandardCharsets.UTF_8)
                .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        model=mock(AgentModelGateway.class);List<CreativeSkill> skills=new ArrayList<>();
        for(var entry:Map.of("script-generation","SCRIPT","storyboard-generation","STORYBOARD","prompt-optimization","PROMPT").entrySet()) {
            var skill=mock(CreativeSkill.class);when(skill.descriptor()).thenReturn(new SkillDescriptor(entry.getKey(),"1",entry.getKey(),json.createObjectNode(),entry.getValue()));skills.add(skill);
        }
        compiler=new RecipeCompiler(model,new SkillRegistry(skills),json);
        catalog=new RecipeCatalog(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),json,compiler);
    }
    RecipeCatalog.ImportTemplate request(RecipeTemplates.Template template,String key) {
        return new RecipeCatalog.ImportTemplate(key,template.version(),template.templateHash());
    }
    // 【测什么】全部平台模板均为实际可执行的有限文字阶段，来源与限制被保留，无模型请求。
    // 【怎么算红】加入媒体阶段/缺失能力/非法依赖/无输入支持，validate或missing断言失败。
    @Test void bundledTemplatesMatchActualTextCapabilities() {
        var templates=catalog.templates(1);assertEquals(9,templates.size());
        assertNotNull(getClass().getResource("/agent/recipe-templates-NOTICES.md"));
        assertEquals(templates,catalog.templates(1));
        for(var template:templates) {
            template.definition().validate();assertTrue(template.missingCapabilities().isEmpty(),template.id());
            assertTrue(template.definition().requiredInputs().isEmpty());assertFalse(template.limitations().isEmpty());
            assertFalse(template.sources().isEmpty());
            for(var source:template.sources()) {
                assertEquals("MIT",source.license());
                assertTrue(template.instruction().contains(source.url()),"来源必须在持久化技能正文保留");
            }
            assertTrue(template.templateHash().matches("[a-f0-9]{64}"));
        }
        assertEquals(401,assertThrows(BusinessException.class,()->catalog.templates(0)).getCode());verifyNoInteractions(model);
    }
    // 【测什么】导入只创建私有草稿及预览；重复请求一次落库，发布固定版本且不调用LLM。
    // 【怎么算红】去掉导入幂等/预览/所有者检查，记录数、发布或越权断言失败。
    @Test void importReplaysAndPublishesWithoutModel() {
        var template=catalog.templates(1).get(0);var request=request(template,"import");
        var draft=catalog.importTemplate(1,template.id(),request);
        assertEquals(draft,catalog.importTemplate(1,template.id(),request));
        assertEquals(0,draft.latestVersion());assertNotNull(draft.compilation());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM creative_recipe",Integer.class));
        assertEquals(404,assertThrows(BusinessException.class,()->catalog.get(2,draft.id())).getCode());
        var preview=draft.compilation();var published=catalog.publish(1,draft.id(),new RecipeCatalog.Publish("publish",1L,preview.id(),preview.definitionHash(),true));
        assertEquals(template.definition(),catalog.requireRunnable(1,published.latestVersionId()).definition());
        catalog.save(1,new RecipeCatalog.Save("edit",draft.id(),published.revision(),"修改标题","描述","修改后的内容"));
        assertEquals(template.definition(),catalog.requireRunnable(1,published.latestVersionId()).definition());
        assertEquals(draft,catalog.importTemplate(1,template.id(),request));
        var other=catalog.importTemplate(2,template.id(),request);assertNotEquals(draft.id(),other.id());verifyNoInteractions(model);
    }
    // 【测什么】过期规格、同key异内容和空参数不可创建新草稿，失败事务不污染command。
    // 【怎么算红】移除version/hash绑定或请求hash检查，409断言失败；提前落库导致记录数非0。
    @Test void versionAndHashMustMatchReviewedTemplate() {
        var template=catalog.templates(1).get(0);
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.importTemplate(1,template.id(),new RecipeCatalog.ImportTemplate("v",template.version()+1,template.templateHash()))).getCode());
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.importTemplate(1,template.id(),new RecipeCatalog.ImportTemplate("h",template.version(),"0".repeat(64)))).getCode());
        assertEquals(400,assertThrows(BusinessException.class,()->catalog.importTemplate(1,template.id(),null)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->catalog.importTemplate(1,"not-present",request(template,"missing"))).getCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM creative_recipe",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM creative_recipe_command",Integer.class));
        catalog.importTemplate(1,template.id(),request(template,"stable"));
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.importTemplate(1,template.id(),new RecipeCatalog.ImportTemplate("stable",template.version()+1,template.templateHash()))).getCode());
    }
    // 【测什么】两个并发导入同一命令只创建同一个私有草稿。
    // 【怎么算红】删除唯一命令约束/重复请求恢复路径，将产生两草稿或请求异常。
    @Test void concurrentImportCreatesOneDraft() throws Exception {
        var template=catalog.templates(1).get(0);var command=request(template,"race");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<RecipeCatalog.RecipeView> action=()->{start.await();return catalog.importTemplate(1,template.id(),command);};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM creative_recipe",Integer.class));
        } finally {pool.shutdownNow();}
    }
}
