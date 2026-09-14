package org.example.seedancegenarate.canvas;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.CanvasEdgeMapper;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.CanvasService;
import org.example.seedancegenarate.service.Impl.CanvasServiceImpl;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CanvasGroupMutationPersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AnnotationConfigApplicationContext beans;
    private JdbcTemplate jdbc;
    private CanvasService service;
    private CanvasNodeMapper nodes;
    private Long canvasId;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:canvas_group_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        // Only this in-memory fixture uses CLOB for JSON: H2 otherwise quotes bound JSON strings.
        jdbc.execute("""
                CREATE TABLE canvas (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT,
                  title VARCHAR(128), viewport CLOB, version BIGINT DEFAULT 0,
                  last_mutation_id VARCHAR(64), status VARCHAR(16), create_time TIMESTAMP, update_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE canvas_node (id BIGINT AUTO_INCREMENT PRIMARY KEY, canvas_id BIGINT,
                  node_key VARCHAR(64), node_type VARCHAR(24), title VARCHAR(128), pos_x INT, pos_y INT,
                  width INT, height INT, config CLOB, status VARCHAR(16), task_id VARCHAR(128),
                  submit_request_id VARCHAR(128), output CLOB, error_msg VARCHAR(512),
                  create_time TIMESTAMP, update_time TIMESTAMP, UNIQUE(canvas_id, node_key))
                """);
        jdbc.execute("""
                CREATE TABLE canvas_edge (id BIGINT AUTO_INCREMENT PRIMARY KEY, canvas_id BIGINT,
                  edge_key VARCHAR(64), from_node_key VARCHAR(64), from_port VARCHAR(32),
                  to_node_key VARCHAR(64), to_port VARCHAR(32), create_time TIMESTAMP, update_time TIMESTAMP,
                  UNIQUE(canvas_id, edge_key), UNIQUE(canvas_id, from_node_key, to_node_key, to_port))
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(CanvasMapper.class);
        configuration.addMapper(CanvasNodeMapper.class);
        configuration.addMapper(CanvasEdgeMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        nodes = sql.getMapper(CanvasNodeMapper.class);
        beans = new AnnotationConfigApplicationContext();
        beans.getBeanFactory().registerSingleton("videoEngineRegistry", mock(VideoEngineRegistry.class));
        beans.scan("org.example.seedancegenarate.canvas");
        beans.refresh();
        CanvasServiceImpl target = new CanvasServiceImpl(sql.getMapper(CanvasMapper.class), nodes,
                sql.getMapper(CanvasEdgeMapper.class), beans.getBean(CanvasNodeTypeRegistry.class),
                List.copyOf(beans.getBeansOfType(CanvasMutationValidator.class).values()), json);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (CanvasService) proxy.getProxy();
        canvasId = service.createCanvas(7L, "GROUP test").getId();
    }

    @AfterEach
    void tearDown() {
        if (beans != null) beans.close();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void existingGraphStillSavesAndReadsItsPortsAndViewport() {
        // 【测什么】无 GROUP 的旧图仍通过真实 SQL 保存、回读端口和旧 viewport。
        // 【怎么算红】让新关系校验器要求每个节点必须归组，此测试保存必须失败。
        seedLegacyGraph();
        var detail = service.getDetail(7L, canvasId);
        assertEquals(2, detail.nodes().size());
        assertEquals("TEXT", detail.nodes().get(0).ports().output());
        assertEquals("prompt", detail.edges().get(0).getToPort());
        assertEquals("{\"x\":1,\"y\":2,\"zoom\":1}", detail.canvas().getViewport());
    }

    @Test
    void groupAdoptClearAndUngroupRoundTripWithoutChangingSourceOrEdges() throws Exception {
        // 【测什么】成组→采用→取消采用→解组经真实 service/mapper 往返，任务、边及绝对坐标不变。
        // 【怎么算红】移除 GROUP 注册、丢 config 写入或解组连带删除成员，往返断言必须失败。
        seedLegacyGraph();
        jdbc.update("UPDATE canvas_node SET status='SUCCESS', task_id='task-1', submit_request_id='req-1', "
                + "output=? WHERE node_key='gen'", "{\"mediaType\":\"VIDEO\",\"url\":\"artifact-key\"}");
        CanvasNode source = read("gen");
        var edgesBefore = service.getDetail(7L, canvasId).edges();
        save("group", 1, List.of(group("g", "{\"memberKeys\":[\"text\",\"gen\",\"result:gen\"],\"color\":\"teal\"}")));
        CanvasNode saved = read("g");
        assertEquals("IDLE", saved.getStatus());
        assertEquals(-120, saved.getPosX());
        assertEquals(80, saved.getPosY());
        assertEquals(720, saved.getWidth());
        assertEquals(420, saved.getHeight());
        assertNull(saved.getTaskId());
        assertNull(saved.getOutput());
        var ports = service.getDetail(7L, canvasId).nodes().stream()
                .filter(n -> "g".equals(n.node().getNodeKey())).findFirst().orElseThrow().ports();
        assertNull(ports.output());
        assertTrue(ports.inputs().isEmpty());
        save("adopt", 2, List.of(group("g", "{\"memberKeys\":[\"result:gen\"],\"adoptedNodeKey\":\"gen\"}")));
        assertEquals(saved.getId(), read("g").getId());
        assertEquals("gen", json.readTree(read("g").getConfig()).path("adoptedNodeKey").asText());
        save("clear", 3, List.of(group("g", "{\"memberKeys\":[\"result:gen\"],\"adoptedNodeKey\":null}")));
        assertTrue(json.readTree(read("g").getConfig()).path("adoptedNodeKey").isNull());
        service.applyMutation(7L, canvasId, mutation("ungroup", 4, null, List.of("g")));
        assertEquals(source, read("gen"));
        assertEquals(edgesBefore, service.getDetail(7L, canvasId).edges());
        assertEquals(2, service.getDetail(7L, canvasId).nodes().size());
    }

    @Test
    void groupAndSourceCanBeCreatedTogetherAndMembershipMovedAtomically() {
        // 【测什么】组先于成员出现在同批 upsert 时可存，成员同批换组按最终状态校验。
        // 【怎么算红】用 existingRows 判成员存在或校验中间态，合法新建/换组必须失败。
        save("create", 0, List.of(group("g1", "{\"memberKeys\":[\"n\",\"result:n\"]}"),
                node("n", "GENERATE", "{}"), group("g2", "{}")));
        save("move", 1, List.of(group("g2", "{\"memberKeys\":[\"n\"]}"), group("g1", "{}")));
        assertEquals("{\"memberKeys\":[\"n\"]}", read("g2").getConfig());
        assertEquals("{}", read("g1").getConfig());
        assertEquals(-120, read("n").getPosX());
    }

    @Test
    void deletedSourceAndUnproducedMirrorDoNotTrapOldCanvas() {
        // 【测什么】源未产出/已删除的镜像、已删除采用源允许继续保存或清空标记。
        // 【怎么算红】要求镜像必须已有 output 或采用源必须存在，后续保存必须失败。
        save("create", 0, List.of(node("n", "GENERATE", "{}"),
                group("g", "{\"memberKeys\":[\"result:n\"],\"adoptedNodeKey\":\"n\"}")));
        service.applyMutation(7L, canvasId, mutation("delete", 1, null, List.of("n")));
        save("later", 2, List.of(group("g", "{\"adoptedNodeKey\":\"n\"}")));
        assertEquals("{\"adoptedNodeKey\":\"n\"}", read("g").getConfig());
        save("clear", 3, List.of(group("g", "{\"adoptedNodeKey\":null}")));
        assertEquals(1, service.getDetail(7L, canvasId).nodes().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"memberKeys\":[\"g\"]}", "{\"memberKeys\":[\"result:g\"]}",
            "{\"memberKeys\":[\"n\",\"n\"]}", "{\"memberKeys\":[\"result:text\"]}",
            "{\"adoptedNodeKey\":\"n\"}", "{\"color\":\"pink\"}", "[]", "broken-json"})
    void invalidGroupsAre400WithNoVersionOrRowChanges(String config) {
        // 【测什么】坏关系/配置通过真实 mutation 返回具体 400，CAS 与节点均不写入。
        // 【怎么算红】移除对应 GROUP 校验使非法 config 落库，400/版本/行数断言必须失败。
        save("seed", 0, List.of(node("n", "GENERATE", "{}"), node("text", "TEXT", "{}")));
        var before = service.getDetail(7L, canvasId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> save("bad", 1, List.of(group("g", config))));
        assertEquals(400, ex.getCode());
        assertFalse(ex.getMessage().isBlank());
        assertEquals(before, service.getDetail(7L, canvasId));
    }

    @Test
    void replayAfterLostResponseKeepsOneGroupAndOneVersionIncrement() {
        // 【测什么】GROUP mutation 响应丢失后原样重放，真实数据库身份和版本都只变一次。
        // 【怎么算红】移除既有 mutationId 重放分支，第二次原请求会冲突或重复写入而变红。
        var request = mutation("lost-response", 0, List.of(group("g", "{}")), null);
        var first = service.applyMutation(7L, canvasId, request);
        var before = service.getDetail(7L, canvasId);
        var replay = service.applyMutation(7L, canvasId, request);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.version(), replay.version());
        assertEquals(before, service.getDetail(7L, canvasId));
    }

    @Test
    void concurrentGroupSavesWithSameBaseVersionHaveExactlyOneWinner() throws Exception {
        // 【测什么】两线程同一 baseVersion 成组，真实 H2 CAS 恰好一个成功、一个 409。
        // 【怎么算红】去掉既有 bumpVersion 的版本条件或忽略 CAS=0，版本/单行/409 断言必须失败。
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    save("concurrent-" + i, 0, List.of(group("g" + i, "{}")));
                    return 200;
                } catch (BusinessException e) {
                    return e.getCode();
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var codes = List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
            assertTrue(codes.contains(200) && codes.contains(409), codes.toString());
            assertEquals(1, service.getDetail(7L, canvasId).nodes().size());
            assertEquals(1L, service.getDetail(7L, canvasId).canvas().getVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void databaseWriteFailureRollsBackCasAndEarlierGroupInsert() {
        // 【测什么】测试库故意拒绝第二行，既有事务回滚 CAS 和先插入的第一组。
        // 【怎么算红】绕开真实 service 的事务拦截，失败后第一组和版本会残留而变红。
        jdbc.execute("ALTER TABLE canvas_node ADD CONSTRAINT injected_write_failure CHECK (title <> 'g2')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> save("failed", 0, List.of(group("g1", "{}"), group("g2", "{}"))));
        var detail = service.getDetail(7L, canvasId);
        assertEquals(0L, detail.canvas().getVersion());
        assertNull(detail.canvas().getLastMutationId());
        assertTrue(detail.nodes().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void groupConnectionsStillFailAtExistingPortValidator(boolean groupIsSource) {
        // 【测什么】GROUP 无端口，作为连线的任一端都由既有端口校验返回 400。
        // 【怎么算红】给 GROUP 增加输出或 prompt 输入端口，对应方向会被允许而变红。
        var edge = new CanvasService.EdgeUpsert("bad-edge", groupIsSource ? "g" : "text", "out",
                groupIsSource ? "gen" : "g", "prompt");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(7L, canvasId, new CanvasService.CanvasMutation("edge", 0L, null,
                        List.of(group("g", "{}"), node("gen", "GENERATE", "{}"), node("text", "TEXT", "{}")),
                        null, List.of(edge), null)));
        assertEquals(400, ex.getCode());
        assertTrue(service.getDetail(7L, canvasId).nodes().isEmpty());
        assertEquals(0L, service.getDetail(7L, canvasId).canvas().getVersion());
    }

    @Test
    void liveDuplicateOwnershipIsRejectedBeforeAnySqlWrite() {
        // 【测什么】真实数据库中已产出的镜像不能在第二组重复归属，拒绝时整图不变。
        // 【怎么算红】从注入列表删除 GroupMembershipValidator，第二组会被写入而变红。
        save("seed", 0, List.of(node("n", "GENERATE", "{}"), group("g1", "{\"memberKeys\":[\"result:n\"]}")));
        jdbc.update("UPDATE canvas_node SET status='SUCCESS', output=? WHERE node_key='n'",
                "{\"mediaType\":\"VIDEO\",\"url\":\"artifact-key\"}");
        var before = service.getDetail(7L, canvasId);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> save("duplicate", 1, List.of(group("g2", "{\"memberKeys\":[\"result:n\"]}"))));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("result:n"));
        assertEquals(before, service.getDetail(7L, canvasId));
    }

    private void seedLegacyGraph() {
        service.applyMutation(7L, canvasId, new CanvasService.CanvasMutation("old", 0L,
                "{\"x\":1,\"y\":2,\"zoom\":1}",
                List.of(node("text", "TEXT", "{\"content\":\"hello\"}"), node("gen", "GENERATE", "{}")),
                null, List.of(new CanvasService.EdgeUpsert("e", "text", "out", "gen", "prompt")), null));
    }

    private CanvasService.NodeUpsert node(String key, String type, String config) {
        return new CanvasService.NodeUpsert(key, type, key, -120, 80, 720, 420, config);
    }

    private CanvasService.NodeUpsert group(String key, String config) {
        return node(key, "GROUP", config);
    }

    private CanvasService.CanvasMutation mutation(String id, long version,
            List<CanvasService.NodeUpsert> upserts, List<String> deletes) {
        return new CanvasService.CanvasMutation(id, version, null, upserts, deletes, null, null);
    }

    private CanvasService.SaveAck save(String id, long version, List<CanvasService.NodeUpsert> upserts) {
        return service.applyMutation(7L, canvasId, mutation(id, version, upserts, null));
    }

    private CanvasNode read(String key) {
        return service.getDetail(7L, canvasId).nodes().stream().map(CanvasService.NodeView::node)
                .filter(n -> key.equals(n.getNodeKey())).findFirst().orElseThrow();
    }
}
