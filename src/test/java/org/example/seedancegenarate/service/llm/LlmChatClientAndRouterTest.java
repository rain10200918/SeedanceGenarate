package org.example.seedancegenarate.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.example.seedancegenarate.config.PromptOptimizeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用一个本机 HTTP 服务当假的 LLM，把「请求长什么样」「各种失败怎么分类」「路由什么时候切」真的跑一遍。
 * 不 mock HttpClient：路由的正确性全押在超时类型上，mock 出来的异常证明不了 JDK 真抛的是哪个类。
 */
class LlmChatClientAndRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Map<String, Object>> MESSAGES = List.of(
            Map.of("role", "system", "content", "你是提示词专家"),
            Map.of("role", "user", "content", "一只猫"));
    private static final LlmCallMeta META = new LlmCallMeta(LlmCallMeta.SCENE_PROMPT_OPTIMIZE, "z-image-turbo");

    private HttpServer server;
    private String base;
    private final List<JsonNode> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuth = new CopyOnWriteArrayList<>();
    private LlmChatClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // 200 + 正常内容
        server.createContext("/ok", ex -> {
            record(ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestBody().readAllBytes());
            reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"  \\\"a red cat\\\"  \"}}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}");
        });
        // 200 但 content 为空（推理内容跑到别的字段）
        server.createContext("/empty", ex -> {
            record(null, ex.getRequestBody().readAllBytes());
            reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"thinking...\"}}]}");
        });
        server.createContext("/unauthorized", ex -> {
            record(null, ex.getRequestBody().readAllBytes());
            reply(ex, 401, "{\"error\":\"invalid api key sk-should-not-leak\"}");
        });
        server.createContext("/boom", ex -> {
            record(null, ex.getRequestBody().readAllBytes());
            reply(ex, 500, "internal");
        });
        server.createContext("/garbage", ex -> {
            record(null, ex.getRequestBody().readAllBytes());
            reply(ex, 200, "<html>not json</html>");
        });
        // 慢：比通道读超时还久才回
        server.createContext("/slow", ex -> {
            record(null, ex.getRequestBody().readAllBytes());
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        client = new LlmChatClient(MAPPER, HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void record(String auth, byte[] body) {
        try {
            receivedBodies.add(MAPPER.readTree(body));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (auth != null) {
            receivedAuth.add(auth);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private LlmChannelSpec channel(String name, String path, int priority, boolean enabled, int timeoutMs,
                                   Double temperature, LlmChannelSpec.TokenParam tokenParam) {
        return new LlmChannelSpec(name, base + path, "sk-" + name + "-key-0123456789", "model-" + name,
                temperature, 777, tokenParam, timeoutMs, priority, enabled, false, null);
    }

    // ───────────────────────── 客户端：请求形状 ─────────────────────────

    @Test
    void requestCarriesBearerKeyModelAndTheChannelsOwnTokenParam() {
        // 【测什么】请求体按通道拼：model、messages、temperature（有就传）、max token 字段名按通道、stream=false；密钥进 Bearer
        // 【怎么算红】(a) token 字段名写死 max_tokens —— 新一代推理模型的服务直接 400，通道永远用不了；
        //            (b) 密钥没进头 —— 全部 401
        LlmChatResponse r = client.chat(
                channel("a", "/ok", 1, true, 5000, 0.7, LlmChannelSpec.TokenParam.MAX_COMPLETION_TOKENS), MESSAGES, META);

        assertEquals("\"a red cat\"", r.content(), "content 只 trim，不剥引号（剥引号是业务层的事）");
        assertEquals(12, r.promptTokens());
        assertEquals(5, r.completionTokens());
        JsonNode body = receivedBodies.get(0);
        assertEquals("model-a", body.get("model").asText());
        assertEquals(0.7, body.get("temperature").asDouble(), 1e-9);
        assertEquals(777, body.get("max_completion_tokens").asInt());
        assertNull(body.get("max_tokens"), "选了 max_completion_tokens 就不能同时发 max_tokens");
        assertFalse(body.get("stream").asBoolean());
        assertEquals(2, body.get("messages").size());
        assertEquals("Bearer sk-a-key-0123456789", receivedAuth.get(0));
    }

    @Test
    void nullTemperatureAndNoneTokenParamAreSimplyOmitted() {
        // 【测什么】temperature 为 null 就不传；token_param=none 就一个 max token 字段都不发
        // 【怎么算红】总是传 temperature —— 推理类模型对这个参数直接 400，「可空 = 不传」形同虚设
        client.chat(channel("b", "/ok", 1, true, 5000, null, LlmChannelSpec.TokenParam.NONE), MESSAGES, META);
        JsonNode body = receivedBodies.get(0);
        assertNull(body.get("temperature"));
        assertNull(body.get("max_tokens"));
        assertNull(body.get("max_completion_tokens"));
    }

    // ───────────────────────── 客户端：失败分类 ─────────────────────────

    @Test
    void httpErrorsAreFailoverableAndNeverEchoTheKeyOrUrl() {
        // 【测什么】401 是可切的快失败；异常消息和短因里不含密钥、不含地址
        // 【怎么算红】把响应体或 URL 拼进异常 —— 第三方 401 的响应体会回显 key，进日志、进用户可见的错误
        LlmChannelException e = assertThrows(LlmChannelException.class,
                () -> client.chat(channel("c", "/unauthorized", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS), MESSAGES, META));
        assertTrue(e.failoverable());
        assertTrue(e.reason().contains("401"));
        assertTrue(e.reason().contains("配置错误"), "401 必须点名是配置错误，不然没人去修");
        String all = e.getMessage() + e.reason();
        assertFalse(all.contains("sk-"), "密钥不许出现在异常里");
        assertFalse(all.contains("127.0.0.1"), "地址不许出现在异常里");
    }

    @Test
    void serverErrorGarbageAndEmptyContentAreAllFailoverable() {
        // 【测什么】5xx / 非 JSON / content 为空，三种都判为可切
        // 【怎么算红】空 content 原样返回 —— 路由认为成功，用户拿到一条空提示词，且切面记成 SUCCESS
        assertTrue(assertThrows(LlmChannelException.class, () -> client.chat(
                channel("d", "/boom", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS), MESSAGES, META)).failoverable());
        assertTrue(assertThrows(LlmChannelException.class, () -> client.chat(
                channel("e", "/garbage", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS), MESSAGES, META)).failoverable());
        LlmChannelException empty = assertThrows(LlmChannelException.class, () -> client.chat(
                channel("f", "/empty", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS), MESSAGES, META));
        assertTrue(empty.failoverable());
        assertTrue(empty.reason().contains("空响应"));
    }

    @Test
    void aRealReadTimeoutIsClassifiedAsNotFailoverable() {
        // 【测什么】真的等到读超时（服务 1.5s 才回，通道 300ms），JDK 抛的类型被判为不可切，且带「缩短」提示
        // 【怎么算红】读超时被当成普通 IO 失败可切 —— 路由会接着试备通道，用户从 100s 等到 120s 照样失败
        LlmChannelException e = assertThrows(LlmChannelException.class, () -> client.chat(
                channel("g", "/slow", 1, true, 300, null, LlmChannelSpec.TokenParam.MAX_TOKENS), MESSAGES, META));
        assertFalse(e.failoverable(), "读超时不许切");
        assertTrue(e.getMessage().contains("缩短"));
    }

    @Test
    void aRefusedConnectionIsAFastFailure() {
        // 【测什么】连不上（端口没人听）是可切的快失败
        // 【怎么算红】IO 异常一律当不可切 —— 主通道进程挂了，降级链永远不触发
        LlmChannelSpec dead = new LlmChannelSpec("dead", "http://127.0.0.1:1/v1/chat/completions", "sk-x-0123456789",
                "m", null, 10, LlmChannelSpec.TokenParam.MAX_TOKENS, 5000, 1, true, false, null);
        assertTrue(assertThrows(LlmChannelException.class, () -> client.chat(dead, MESSAGES, META)).failoverable());
    }

    // ───────────────────────── 路由 ─────────────────────────

    private LlmRouter routerWith(LlmChannelSpec... specs) {
        LlmChannelRegistry registry = mock(LlmChannelRegistry.class);
        List<LlmChannelSpec> all = List.of(specs);
        when(registry.channels()).thenReturn(all);
        when(registry.routable()).thenReturn(all.stream().filter(LlmChannelSpec::routable).toList());
        for (LlmChannelSpec s : specs) {
            when(registry.find(s.name())).thenReturn(s);
        }
        return new LlmRouter(registry, client);
    }

    @Test
    void primaryFastFailureFallsOverToTheNextChannel() {
        // 【测什么】主通道 500 → 切到备通道，用户拿到备通道的结果
        // 【怎么算红】不循环、第一条失败就抛 —— 有备通道等于没有
        LlmRouter router = routerWith(
                channel("primary", "/boom", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS),
                channel("backup", "/ok", 2, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS));
        LlmChatResponse r = router.chat(MESSAGES, META);
        assertEquals("\"a red cat\"", r.content());
        assertEquals(2, receivedBodies.size(), "主通道试过一次、备通道一次");
        assertEquals("model-backup", receivedBodies.get(1).get("model").asText());
    }

    @Test
    void primaryReadTimeoutDoesNotTouchTheBackup() {
        // 【测什么】主通道读超时 → 直接抛，备通道一次都不被调用
        // 【怎么算红】读超时也切 —— 备通道被调用；前端 120s 墙内主通道已用掉 100s，切过去只是让用户多等
        AtomicInteger backupHits = new AtomicInteger();
        server.createContext("/backup-counter", ex -> {
            backupHits.incrementAndGet();
            record(null, ex.getRequestBody().readAllBytes());
            reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"backup\"}}]}");
        });
        LlmRouter router = routerWith(
                channel("primary", "/slow", 1, true, 300, null, LlmChannelSpec.TokenParam.MAX_TOKENS),
                channel("backup", "/backup-counter", 2, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS));
        LlmChannelException e = assertThrows(LlmChannelException.class, () -> router.chat(MESSAGES, META));
        assertFalse(e.failoverable());
        assertEquals(0, backupHits.get(), "读超时后不许再碰备通道");
    }

    @Test
    void allChannelsFastFailingThrowsTheLastFailure() {
        // 【测什么】两条都快失败 → 抛出，消息是通用的「请稍后再试」，且每条都真的被试过
        // 【怎么算红】吞掉异常返回 null/空 —— 业务层拿到空内容，用户看到一条空提示词
        LlmRouter router = routerWith(
                channel("p", "/boom", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS),
                channel("q", "/unauthorized", 2, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS));
        LlmChannelException e = assertThrows(LlmChannelException.class, () -> router.chat(MESSAGES, META));
        assertTrue(e.getMessage().contains("稍后再试"));
        assertEquals(2, receivedBodies.size());
    }

    @Test
    void noRoutableChannelSaysNotConfiguredInsteadOfBlowingUp() {
        // 【测什么】一条启用通道都没有 → 「未配置，请联系管理员」
        // 【怎么算红】空列表直接 NPE / 或抛通用错 —— 管理员看到「稍后再试」以为是瞬时故障，其实是自己把通道全关了
        LlmRouter router = routerWith(channel("off", "/ok", 1, false, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS));
        RuntimeException e = assertThrows(RuntimeException.class, () -> router.chat(MESSAGES, META));
        assertTrue(e.getMessage().contains("未配置"));
        assertEquals(0, receivedBodies.size());
    }

    @Test
    void trialCanTargetADisabledChannelButNeverFallsBackSilently() {
        // 【测什么】试跑能指到停用的通道；指到不存在的名字响亮失败，不回落路由
        // 【怎么算红】chatWith 找不到就走 chat() —— 你以为在测新通道，其实跑在老通道上，
        //            然后得出「新通道没问题」的错误结论
        LlmRouter router = routerWith(
                channel("live", "/boom", 1, true, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS),
                channel("candidate", "/ok", 2, false, 5000, null, LlmChannelSpec.TokenParam.MAX_TOKENS));
        LlmChatResponse r = router.chatWith("candidate", MESSAGES, META);
        assertEquals("\"a red cat\"", r.content());
        assertEquals("model-candidate", receivedBodies.get(0).get("model").asText());
        assertThrows(IllegalArgumentException.class, () -> router.chatWith("typo", MESSAGES, META));
        assertEquals(1, receivedBodies.size(), "不存在的名字一次请求都不许发");
    }
}
