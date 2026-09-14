package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.TaskCallerView;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskCallerTest {
    @Mock VideoTaskService service;
    @Mock ArtifactExpiryPolicy expiry;
    @Mock ContentModerationPolicy moderation;
    @InjectMocks VideoController controller;
    AutoCloseable mocks;
    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        var user = new AppUser(); user.setId(7L); user.setRole("USER"); UserContext.setUser(user);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""), VideoTask.class);
    }
    @AfterEach void cleanup() throws Exception { UserContext.clear(); if (mocks != null) mocks.close(); }

    // 【测什么】详情先执行用户隔离查询，再补显示字段；响应没有凭据投影。
    // 【怎么算红】去掉user_id过滤、调用者赋值或未先授权就联查，断言失败。
    @Test void authorizedDetailEnrichesAfterOwnerLookup() {
        var task = new VideoTask(); task.setId(42L); task.setUserId(7L);
        when(service.getOne(any(Wrapper.class), eq(false))).thenReturn(task);
        when(service.getCaller(42L)).thenReturn(new TaskCallerView("测试用户", "剪辑脚本"));
        assertSame(task, controller.task("business-task").getData());
        assertEquals("测试用户", task.getCallerName()); assertEquals("剪辑脚本", task.getApiKeyName());
        var captor = ArgumentCaptor.forClass(Wrapper.class);
        var ordered = inOrder(service); ordered.verify(service).getOne(captor.capture(), eq(false)); ordered.verify(service).getCaller(42L);
        assertTrue(captor.getValue().getSqlSegment().contains("user_id"));
        verify(moderation).redact(task);
    }

    // 【测什么】未登录或无可访问任务时不查询名字。
    // 【怎么算红】授权之前补查调用者，verifyNoInteractions或never失败。
    @Test void missingOrUnauthorizedTaskDoesNotReadNames() {
        assertThrows(RuntimeException.class, () -> controller.task("other"));
        verify(service, never()).getCaller(any());
        reset(service); UserContext.clear();
        assertThrows(RuntimeException.class, () -> controller.task("other")); verifyNoInteractions(service);
    }

    // 【测什么】管理员沿用跨用户权限，缺失关联不使详情失败。
    // 【怎么算红】管理员强加user_id条件或空关联解引用，测试失败。
    @Test void adminAndMissingRelationRemainReadable() {
        UserContext.getUser().setRole("ADMIN");
        var task = new VideoTask(); task.setId(43L); task.setUserId(8L);
        when(service.getOne(any(Wrapper.class), eq(false))).thenReturn(task);
        assertSame(task, controller.task("43").getData()); assertNull(task.getCallerName());
        var captor = ArgumentCaptor.forClass(Wrapper.class); verify(service).getOne(captor.capture(), eq(false));
        assertFalse(captor.getValue().getSqlSegment().contains("user_id"));
    }

    // 【测什么】实际MyBatis LEFT JOIN读取当前名字，缺失/错属主Key不泄露、不丢任务。
    // 【怎么算红】改INNER JOIN、删Key属主条件、映射列错误，结果断言失败。
    @Test void realJoinSupportsMissingRelationsAndRejectsForeignKeyOwner() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:caller-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(ds);
        var table = com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(VideoTask.class);
        String columns = table.getFieldList().stream().map(f -> f.getColumn() + " VARCHAR(1000)")
                .collect(java.util.stream.Collectors.joining(","));
        db.execute("CREATE TABLE video_task(id BIGINT PRIMARY KEY," + columns + ")");
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,username VARCHAR(64))");
        db.execute("CREATE TABLE api_key(id BIGINT PRIMARY KEY,user_id BIGINT,name VARCHAR(64),key_hash VARCHAR(64))");
        db.update("INSERT INTO app_user VALUES(7,'Alice')");
        db.update("INSERT INTO api_key VALUES(5,7,'render','secret'),(6,8,'foreign','hidden'),(7,99,'orphan','hidden')");
        db.update("INSERT INTO video_task(id,user_id,api_key_id) VALUES(1,7,5),(2,7,NULL),(3,7,99),(4,7,6),(5,99,7)");
        db.update("UPDATE video_task SET biz_task_id='biz-owned',task_id='legacy-owned' WHERE id=1");
        var config = new MybatisConfiguration(); config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds)); config.addMapper(VideoTaskMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(VideoTaskMapper.class);
            assertEquals(new TaskCallerView("Alice", "render"), mapper.selectCaller(1L));
            assertEquals(new TaskCallerView("Alice", null), mapper.selectCaller(2L));
            assertEquals(new TaskCallerView("Alice", null), mapper.selectCaller(3L));
            assertEquals(new TaskCallerView("Alice", null), mapper.selectCaller(4L));
            assertEquals(new TaskCallerView(null, "orphan"), mapper.selectCaller(5L));
            db.update("UPDATE app_user SET username='Renamed' WHERE id=7"); session.clearCache();
            assertEquals("Renamed", mapper.selectCaller(1L).callerName());
            assertNull(mapper.selectCaller(999L));
            // 【测什么】用controller实际生成的归属Wrapper执行SQL，业务/旧/数字ID均拒绝他人。
            // 【怎么算红】user_id绑定错值或OR逃出归属分组，会读出他人任务而不抛异常。
            when(service.getOne(any(Wrapper.class), eq(false))).thenAnswer(call ->
                    mapper.selectOne(call.getArgument(0), false));
            UserContext.getUser().setId(8L);
            for (String id : java.util.List.of("biz-owned", "legacy-owned", "1")) {
                assertThrows(RuntimeException.class, () -> controller.task(id));
            }
            verify(service, never()).getCaller(any());
            UserContext.getUser().setId(7L);
            for (String id : java.util.List.of("biz-owned", "legacy-owned", "1")) {
                assertEquals(1L, ((VideoTask) controller.task(id).getData()).getId());
            }
        }
    }
}
