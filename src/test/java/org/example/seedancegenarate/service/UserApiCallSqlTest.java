package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.example.seedancegenarate.controller.*;
import org.example.seedancegenarate.dto.*;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.interceptor.AuthInterceptor;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserApiCallSqlTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}
    AnnotationConfigApplicationContext context;
    JdbcTemplate db;
    UserApiCallService service;
    ApiCallLogMapper mapper;
    MockMvc mvc;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static final UserApiCallQuery ALL = new UserApiCallQuery(null,null,null,null,null,null,null);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:calls_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE api_key(id BIGINT PRIMARY KEY,user_id BIGINT,name VARCHAR(64),key_prefix VARCHAR(32),status VARCHAR(16))");
        db.execute("CREATE TABLE api_call_log(id BIGINT PRIMARY KEY, user_id BIGINT,api_key_id BIGINT,request_id VARCHAR(64),task_id VARCHAR(128),"
                + "model VARCHAR(64),provider VARCHAR(32),status VARCHAR(16),http_code INT,error_code VARCHAR(32),cost_amount DECIMAL(12,2),"
                + "create_time TIMESTAMP,update_time TIMESTAMP,queued_ms BIGINT,generate_ms BIGINT,total_ms BIGINT,"
                + "error_msg VARCHAR(512),client_ip VARCHAR(64),user_agent VARCHAR(64),request_fingerprint VARCHAR(64))");
        db.update("INSERT INTO api_key VALUES(10,7,'mine','sk-mine','ENABLED'),(11,7,'revoked','sk-old','DISABLED'),(20,8,'foreign','sk-secret','ENABLED')");
        add(1,7,10,"SUCCESS","m1","p1",null,"1.25","2026-09-15 00:00:00");
        add(2,7,11,"FAILED","m2","p1","UPSTREAM","0.00","2026-09-15 01:00:00");
        add(3,7,99,"REJECTED","m1","p2","INVALID",null,"2026-09-15 02:00:00");
        add(4,7,10,"RECEIVED","m1","p1",null,null,"2026-09-15 03:00:00");
        add(5,8,20,"SUCCESS","m1","p1","FOREIGN","99.00","2026-09-15 00:00:00");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),ds));
        config.addMapper(ApiCallLogMapper.class);
        var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper = spy(template.getMapper(ApiCallLogMapper.class));
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> ds);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(ApiCallLogMapper.class, () -> mapper);
        context.registerBean(UserApiCallService.class); context.refresh();
        service = context.getBean(UserApiCallService.class);
        var tokens = mock(UserTokenService.class);
        when(tokens.getUserByToken(any())).thenAnswer(call -> {
            String token = call.getArgument(0);
            if (token == null) return null;
            var user = new AppUser(); user.setId("other".equals(token) ? 8L : 7L);
            user.setRole("admin".equals(token) ? "ADMIN" : "USER"); return user;
        });
        mvc = MockMvcBuilders.standaloneSetup(new UserApiCallController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(tokens,mock(UserActivityService.class),json)).build();
    }
    void add(long id,long owner,long key,String status,String model,String provider,String error,String cost,String time) {
        db.update("INSERT INTO api_call_log(id,user_id,api_key_id,request_id,task_id,model,provider,status,http_code,error_code,cost_amount,create_time,update_time,queued_ms,generate_ms,total_ms,error_msg,client_ip,user_agent,request_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,10,90,100,'private error','private ip','private agent','private fingerprint')",
                id,owner,key,"req-"+id,"task-"+id,model,provider,status,200,error,cost,time,time);
    }
    @AfterEach void close() { if(context != null) context.close(); }

    // 【测什么】真实SQL按日志属主隔离、id倒序，撤销和缺失key保留，汇总只计算本人日志。
    // 【怎么算红】删user_id条件、改INNER JOIN或漏状态/金额聚合，结果断言失败。
    @Test void ownershipHistoryAndTotals() {
        var page = service.page(7,1,20,ALL);
        assertEquals(List.of(4L,3L,2L,1L),page.getRecords().stream().map(UserApiCallView::id).toList());
        assertNull(page.getRecords().get(1).apiKeyName());
        assertEquals("revoked",page.getRecords().get(2).apiKeyName());
        var summary = service.summary(7,ALL);
        assertEquals(4,summary.total()); assertEquals(1,summary.received()); assertEquals(1,summary.success());
        assertEquals(1,summary.failed()); assertEquals(1,summary.rejected());
        assertEquals(0,summary.totalCost().compareTo(new java.math.BigDecimal("1.25")));
        assertEquals(List.of(new UserApiCallSummary.ErrorCodeCount("INVALID",1),new UserApiCallSummary.ErrorCodeCount("UPSTREAM",1)),summary.byErrorCode());
        assertEquals(List.of(5L),service.page(8,1,20,ALL).getRecords().stream().map(UserApiCallView::id).toList());
    }

    // 【测什么】每种筛选及组合的分页总数与SQL汇总一致，时间左闭右开、外来key空集。
    // 【怎么算红】任一查询漏筛选或把to改成包含，预期ID及汇总计数失败。
    @Test void filtersAndPagination() {
        check(new UserApiCallQuery(11L,null,null,null,null,null,null),List.of(2L));
        check(new UserApiCallQuery(20L,null,null,null,null,null,null),List.of());
        check(new UserApiCallQuery(99L,null,null,null,null,null,null),List.of());
        check(new UserApiCallQuery(null,"m2",null,null,null,null,null),List.of(2L));
        check(new UserApiCallQuery(null,null,"p2",null,null,null,null),List.of(3L));
        check(new UserApiCallQuery(null,null,null,"RECEIVED",null,null,null),List.of(4L));
        check(new UserApiCallQuery(null,null,null,null,"INVALID",null,null),List.of(3L));
        check(new UserApiCallQuery(null,null,null,null,null,"2026-09-15T01:00:00","2026-09-15T03:00:00"),List.of(3L,2L));
        check(new UserApiCallQuery(10L,"m1","p1","SUCCESS",null,"2026-09-15T00:00:00","2026-09-15T01:00:00"),List.of(1L));
        assertEquals(List.of(2L,1L),service.page(7,2,2,ALL).getRecords().stream().map(UserApiCallView::id).toList());
        assertTrue(service.page(7,100,100,ALL).getRecords().isEmpty());
    }
    void check(UserApiCallQuery query,List<Long> ids) {
        var page = service.page(7,1,20,query); var summary = service.summary(7,query);
        assertEquals(ids,page.getRecords().stream().map(UserApiCallView::id).toList());
        assertEquals(ids.size(),page.getTotal()); assertEquals(ids.size(),summary.total());
        assertEquals(summary.total(),summary.received()+summary.success()+summary.failed()+summary.rejected());
        assertEquals(page.getRecords().stream().filter(r -> "RECEIVED".equals(r.status())).count(),summary.received());
        assertEquals(page.getRecords().stream().filter(r -> "SUCCESS".equals(r.status())).count(),summary.success());
        assertEquals(page.getRecords().stream().filter(r -> "FAILED".equals(r.status())).count(),summary.failed());
        assertEquals(page.getRecords().stream().filter(r -> "REJECTED".equals(r.status())).count(),summary.rejected());
        var cost = page.getRecords().stream().map(UserApiCallView::costAmount).filter(Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertEquals(0,cost.compareTo(summary.totalCost()));
        Map<String,Long> errors = new TreeMap<>();
        page.getRecords().stream().map(UserApiCallView::errorCode).filter(e -> e != null && !e.isEmpty())
                .forEach(e -> errors.merge(e,1L,Long::sum));
        assertEquals(errors,summary.byErrorCode().stream().collect(java.util.stream.Collectors.toMap(
                UserApiCallSummary.ErrorCodeCount::errorCode,UserApiCallSummary.ErrorCodeCount::count)));
        if(ids.isEmpty()) { assertEquals(0,summary.totalCost().signum()); assertTrue(summary.byErrorCode().isEmpty()); }
    }

    // 【测什么】A日志错关联B key时保留日志但隐藏名字，显式外来/缺失key筛选均为空。
    // 【怎么算红】删JOIN属主条件或筛选不要求现存本人key，会泄露名字或返回脏日志。
    @Test void dirtyForeignAndMissingKeyFilters() {
        add(6,7,20,"FAILED","m1","p1","DIRTY","0.50","2026-09-15 04:00:00");
        var row = service.page(7,1,20,ALL).getRecords().get(0);
        assertEquals(6L,row.id()); assertNull(row.apiKeyName()); assertNull(row.keyPrefix());
        check(new UserApiCallQuery(20L,null,null,null,null,null,null),List.of());
        check(new UserApiCallQuery(99L,null,null,null,null,null,null),List.of());
        check(new UserApiCallQuery(11L,null,null,null,null,null,null),List.of(2L));
    }

    // 【测什么】真实路由/服务拒绝分页溢出、非法状态和严格本地时间，返回真正400。
    // 【怎么算红】省略校验或使用会吞offset的解析器，至少一项会返回200。
    @Test void actualValidationIsHttp400() throws Exception {
        for (String[] pair : new String[][]{{"current","0"},{"current","-1"},{"size","0"},{"size","101"},
                {"current","9223372036854775807"},{"apiKeyId","0"},{"status","UNKNOWN"},
                {"model","x".repeat(65)},{"provider","x".repeat(33)},{"errorCode","x".repeat(33)},
                {"from","2026-02-30T00:00:00"},{"from","2026-09-15T00:00:00Z"},
                {"from","2026-09-15T00:00:00+08:00"},{"to","2026-09-15T00:00:00-05:00"},
                {"from","2026-09-15"},{"from",""}}) {
            mvc.perform(get("/api/user/api-calls").header("Authorization","Bearer mine").param(pair[0],pair[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
            if(!Set.of("current","size").contains(pair[0])) {
                mvc.perform(get("/api/user/api-calls/summary").header("Authorization","Bearer mine").param(pair[0],pair[1]))
                        .andExpect(status().isBadRequest());
            }
        }
        for(String end : List.of("2026-09-15T00:00:00","2026-09-14T00:00:00")) {
            for(String path : List.of("","/summary")) mvc.perform(get("/api/user/api-calls"+path)
                    .header("Authorization","Bearer mine").param("from","2026-09-15T00:00:00").param("to",end))
                    .andExpect(status().isBadRequest());
        }
    }

    // 【测什么】管理员真实鉴权进入SQL仍只有本人；JSON字段白名单完整，无内部信息。
    // 【怎么算红】开放管理员范围、序列化实体或漏公开字段，精确字段及数据断言失败。
    @Test void httpProjectionAndAdminIsolation() throws Exception {
        var result = mvc.perform(get("/api/user/api-calls").header("Authorization","Bearer admin")
                .param("userId","8")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(4))
                .andReturn().getResponse().getContentAsString();
        var root = json.readTree(result); var fields = new HashSet<String>();
        System.out.println("USER_API_CALL_LIST_JSON=" + result);
        assertTrue(root.at("/data/records/0/createTime").isTextual());
        assertEquals("2026-09-15T03:00:00",root.at("/data/records/0/createTime").textValue());
        assertEquals("2026-09-15T03:00:00",root.at("/data/records/0/updateTime").textValue());
        root.at("/data/records/0").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id","requestId","taskId","apiKeyId","apiKeyName","keyPrefix","model","provider",
                "status","httpCode","errorCode","costAmount","currency","createTime","updateTime","queuedMs","generateMs","totalMs"),fields);
        assertFalse(result.contains("private")); assertFalse(result.contains("sk-secret"));
        String summary = mvc.perform(get("/api/user/api-calls/summary").header("Authorization","Bearer admin"))
                .andExpect(jsonPath("$.data.total").value(4)).andExpect(jsonPath("$.data.currency").value("CNY"))
                .andReturn().getResponse().getContentAsString();
        System.out.println("USER_API_CALL_SUMMARY_JSON=" + summary);
        fields.clear(); json.readTree(summary).at("/data").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("total","received","success","failed","rejected","totalCost","currency","byErrorCode"),fields);
        mvc.perform(get("/api/user/api-calls").header("Authorization","Bearer other"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records[0].id").value(5));
    }

    // 【测什么】Spring实际事务内第一次聚合后，独立连接提交更新，第二次聚合仍读原快照。
    // 【怎么算红】删除@Transactional或降为READ_COMMITTED，会看到新错误码或事务断言失败。
    @Test void summaryUsesRealRepeatableReadSnapshot() throws Exception {
        var dataSource = context.getBean(DataSource.class);
        doAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                    DataSourceUtils.getConnection(dataSource).getTransactionIsolation());
            try(var separate = dataSource.getConnection(); var statement = separate.createStatement()) {
                assertTrue(separate.getAutoCommit());
                assertEquals(1,statement.executeUpdate("UPDATE api_call_log SET status='FAILED',error_code='CHANGED' WHERE id=4"));
            }
            return call.callRealMethod();
        }).when(mapper).selectUserErrorCounts(eq(7L),any(),any(),any());
        var result = service.summary(7,ALL);
        assertEquals(1,result.received()); assertEquals(1,result.failed());
        assertEquals(List.of(new UserApiCallSummary.ErrorCodeCount("INVALID",1),
                new UserApiCallSummary.ErrorCodeCount("UPSTREAM",1)),result.byErrorCode());
        assertEquals("CHANGED",db.queryForObject("SELECT error_code FROM api_call_log WHERE id=4",String.class));
        verify(mapper,never()).selectUserCalls(anyLong(),any(),any(),any(),anyLong(),anyLong());
        System.out.println("USER_API_CALL_SNAPSHOT=real Spring RR readOnly transaction; independent commit visible after summary, hidden inside summary");
    }

    // 【测什么】两账号并发GET及重复读取各自数据，查询无日志或key业务写入。
    // 【怎么算红】用共享属主状态替代会话/SQL参数、GET修改日志，交叉响应或前后数据比较失败。
    @Test void concurrentOwnersAndRepeatedReads() throws Exception {
        var before = db.queryForList("SELECT * FROM api_call_log ORDER BY id");
        var keys = db.queryForList("SELECT * FROM api_key ORDER BY id");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for(String token : List.of("mine","other")) jobs.add(pool.submit(() -> {
                start.await();
                for(int n=0;n<3;n++) {
                    mvc.perform(get("/api/user/api-calls").header("Authorization","Bearer "+token))
                            .andExpect(jsonPath("$.data.total").value(token.equals("mine") ? 4 : 1));
                    mvc.perform(get("/api/user/api-calls/summary").header("Authorization","Bearer "+token))
                            .andExpect(jsonPath("$.data.total").value(token.equals("mine") ? 4 : 1));
                }
                return null;
            }));
            start.countDown();
            for(var job : jobs) job.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(before,db.queryForList("SELECT * FROM api_call_log ORDER BY id"));
        assertEquals(keys,db.queryForList("SELECT * FROM api_key ORDER BY id"));
    }
}
