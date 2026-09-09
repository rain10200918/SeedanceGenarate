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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipeCatalogTest {
    JdbcTemplate db; RecipeCatalog catalog; RecipeCompiler compiler; AgentModelGateway model; final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:recipe"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(ds);
        String sql=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V38__creative_recipe.sql")).readAllBytes(),StandardCharsets.UTF_8)
                .replaceAll("(?i)\\bJSON\\b","TEXT").replaceAll("(?i)\\) ENGINE\\s*=.*?;", ");");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(ds);
        model=mock(AgentModelGateway.class);when(model.defaultChannel()).thenReturn("private");
        CreativeSkill skill=mock(CreativeSkill.class);when(skill.descriptor()).thenReturn(new SkillDescriptor("script-generation","1","script",json.createObjectNode(),"SCRIPT"));
        compiler=new RecipeCompiler(model,new SkillRegistry(List.of(skill)),json);
        catalog=new RecipeCatalog(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),json,compiler);
        when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn(json.writeValueAsString(definition("script-generation","SCRIPT")));
    }
    RecipeDefinition definition(String skill,String type) {
        return new RecipeDefinition("校园导演",List.of(),List.of(),List.of(new RecipeDefinition.Stage("script","脚本","制作脚本",List.of(skill),List.of(),null,false,type)),List.of(skill),List.of(new RecipeDefinition.AcceptanceRule("ARTIFACT_EXISTS",type)));
    }
    RecipeCatalog.RecipeView save() {return catalog.save(1,new RecipeCatalog.Save("save",null,null,"校园导演","校园策划","先写脚本"));}
    RecipeCatalog.Publish publishRequest(RecipeCatalog.RecipeView preview,String key) {return new RecipeCatalog.Publish(key,preview.revision(),preview.compilation().id(),preview.compilation().definitionHash(),true);}
    // 【测什么】无可用模型也能保存草稿，重复创建仅一条；同key不同内容冲突。
    // 【怎么算红】保存调用LLM或移除命令幂等，verifyNoInteractions/记录数/冲突断言失败。
    @Test void draftNeedsNoModelAndCreateReplayIsStable() {
        var first=save();assertEquals(first,save());verifyNoInteractions(model);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM creative_recipe",Integer.class));
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.save(1,new RecipeCatalog.Save("save",null,null,"变更","描述","文本"))).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->catalog.get(2,first.id())).getCode());
    }
    // 【测什么】编译在事务外并只做预览；发布绑定精确hash，重复发布不多版本，旧版本不随草稿变。
    // 【怎么算红】网络移进事务/去除hash守卫/版本从草稿取名，事务、冲突或旧名断言失败。
    @Test void previewAndPublishBindImmutableVersion() throws Exception {
        var draft=save();when(model.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return json.writeValueAsString(definition("script-generation","SCRIPT"));});
        var preview=catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L));
        assertEquals(0,preview.latestVersion());assertEquals(preview,catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L)));
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.publish(1,draft.id(),new RecipeCatalog.Publish("bad",1L,preview.compilation().id(),"wrong",true))).getCode());
        var request=publishRequest(preview,"publish");var published=catalog.publish(1,draft.id(),request);assertEquals(published,catalog.publish(1,draft.id(),request));
        catalog.save(1,new RecipeCatalog.Save("edit",draft.id(),published.revision(),"新名字","新描述","新原文"));
        var old=catalog.requireRunnable(1,published.latestVersionId());assertEquals("校园导演",old.name());assertEquals("先写脚本",old.instruction());
        verify(model,times(1)).complete(any(),anyString(),anyString(),anyString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM creative_recipe_version",Integer.class));
    }
    // 【测什么】源文并发变化使迟到编译不能成为可发布预览。
    // 【怎么算红】去掉编译完成时revision复验，旧编译会成功且污染新草稿。
    @Test void lateCompileCannotPublishOverEditedDraft() throws Exception {
        var draft=save();when(model.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{
            catalog.save(1,new RecipeCatalog.Save("edit",draft.id(),1L,"新名字","描述","新原文"));return json.writeValueAsString(definition("script-generation","SCRIPT"));});
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L))).getCode());
        assertNull(catalog.get(1,draft.id()).compilation());
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L))).getCode());
        verify(model,times(1)).complete(any(),anyString(),anyString(),anyString());
    }
    // 【测什么】模型调用未返回时同key重试不再次发出请求；失败key也不会盲重试。
    // 【怎么算红】去掉RUNNING/FAILED命令检查，重入模型次数断言失败或递归报错。
    @Test void runningAndFailedCompileNeverRecallProvider() {
        var draft=save();when(model.complete(any(),anyString(),anyString(),anyString())).thenAnswer(a->{
            assertEquals(425,assertThrows(BusinessException.class,()->catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L))).getCode());throw new BusinessException(503,"offline");});
        assertEquals(422,assertThrows(BusinessException.class,()->catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L))).getCode());
        assertEquals(422,assertThrows(BusinessException.class,()->catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L))).getCode());
        verify(model,times(1)).complete(any(),anyString(),anyString(),anyString());
    }
    // 【测什么】缺失能力可发布但无法执行，停用不修改已绑定版本，越权读取不可行。
    // 【怎么算红】移除requireRunnable能力/启停或owner检查，对应assertThrows失败。
    @Test void missingCapabilityAndDisabledRecipeCannotStart() throws Exception {
        var draft=save();when(model.complete(any(),anyString(),anyString(),anyString())).thenReturn(json.writeValueAsString(definition("merge-video","VIDEO")));
        var preview=catalog.compile(1,draft.id(),new RecipeCatalog.Compile("compile",1L));assertEquals(List.of("merge-video","stage:VIDEO"),preview.compilation().missingCapabilities());
        var published=catalog.publish(1,draft.id(),publishRequest(preview,"publish"));
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.requireRunnable(1,published.latestVersionId())).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->catalog.published(2,published.latestVersionId(),false)).getCode());
        var disabled=catalog.enable(1,draft.id(),new RecipeCatalog.Enable("disable",published.revision(),false));
        assertFalse(disabled.enabled());assertEquals("校园导演",catalog.published(1,published.latestVersionId(),false).name());
        assertEquals(409,assertThrows(BusinessException.class,()->catalog.requireRunnable(1,published.latestVersionId())).getCode());
    }
    // 【测什么】两个页面用相同revision并发保存，只有一个成功，不静默覆盖。
    // 【怎么算红】移除revision比较，两个Future均成功，成功计数变成2。
    @Test void concurrentEditsHaveExactlyOneWinner() throws Exception {
        var draft=save();var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();for(int n=0;n<2;n++){final int number=n;results.add(pool.submit(()->{
                ready.countDown();go.await();try{catalog.save(1,new RecipeCatalog.Save("edit"+number,draft.id(),1L,"新版"+number,"描述","正文"));return true;}catch(BusinessException e){assertEquals(409,e.getCode());return false;}
            }));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int winners=0;for(var future:results)if(future.get(5,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(2,catalog.get(1,draft.id()).revision());
        } finally {pool.shutdownNow();}
    }
    // 【测什么】严格解析拒绝代码式policy、额外字段、畸形数据；非法原文边界返回4xx。
    // 【怎么算红】关闭FAIL_ON_UNKNOWN_PROPERTIES或删除边界校验，恶意policy/空名称可成功。
    @Test void strictDefinitionAndRequestBoundaries() throws Exception {
        var malicious=json.valueToTree(definition("script-generation","SCRIPT"));((com.fasterxml.jackson.databind.node.ObjectNode)malicious).put("policy","skip-all-payments()");
        assertThrows(BusinessException.class,()->compiler.parse(malicious.toString()));
        assertThrows(BusinessException.class,()->compiler.parse("null"));assertThrows(BusinessException.class,()->compiler.parse("{}"));
        assertEquals(400,assertThrows(BusinessException.class,()->catalog.save(1,new RecipeCatalog.Save("save",null,null," ","描述","正文"))).getCode());
        assertEquals(400,assertThrows(BusinessException.class,()->catalog.save(1,new RecipeCatalog.Save("save",null,null,"名","描述","x".repeat(24001)))).getCode());
        var draft=save();assertEquals(400,assertThrows(BusinessException.class,()->catalog.publish(1,draft.id(),new RecipeCatalog.Publish("p",1L,"x","h",false))).getCode());
        verifyNoInteractions(model);
    }
    // 【测什么】能力输出类型不匹配及本批无绑定入口的输入被明确列为不可执行。
    // 【怎么算红】移除missing的输入或输出匹配检查，对应缺口断言失败。
    @Test void capabilitiesIncludeActualResultTypeAndInputSupport() {
        assertEquals(List.of("output:script-generation:VIDEO","stage:VIDEO"),compiler.missing(definition("script-generation","VIDEO")));
        var base=definition("script-generation","SCRIPT");
        var withInput=new RecipeDefinition(base.instruction(),List.of(new RecipeDefinition.RequiredInput("IMAGE",1)),base.variables(),base.stages(),base.requiredCapabilities(),base.acceptanceRules());
        assertEquals(List.of("input:IMAGE"),compiler.missing(withInput));
    }
}
