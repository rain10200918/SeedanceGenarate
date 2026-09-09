package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.SeedanceConfig;
import org.example.seedancegenarate.engine.SubmissionNotAcceptedException;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 回归防护：方舟 HTTP 调用必须带超时。
 * 对端 accept 后不回一个字节（模拟 Ark 挂起）时，调用必须在读超时内抛错返回，
 * 而不是无限阻塞（无限阻塞 = 提交线程焊死 + fixedDelay 轮询线程永久卡住）。
 */
class SeedanceServiceTimeoutTest {

    @Test
    void receivedNonTimeout4xxProvesRequestWasRejected() throws Exception {
        // 【测什么】已收到 422 等非 408 的 4xx 时翻译成“明确未接单”，允许有限安全重试。
        // 【怎么算红】仍抛普通 RuntimeException 时 attempt 会误进 UNKNOWN 并长期冻结占槽。
        try (TestResponseServer server = new TestResponseServer(422, "{\"error\":\"invalid\"}")) {
            SeedanceServiceImpl service = new SeedanceServiceImpl(config(server.url()), new ObjectMapper());

            assertThrows(SubmissionNotAcceptedException.class,
                    () -> service.generate(List.of(), "prompt", 5, "16:9", null));
        }
    }

    @Test
    void serverErrorRemainsUnknownRatherThanSafeRetry() throws Exception {
        // 【测什么】5xx 仍是结果未知，绝不能因服务端错误盲目重复远端生成。
        // 【怎么算红】把所有非 2xx 都改 typed exception 会让 503 进入 SAFE_RETRY。
        try (TestResponseServer server = new TestResponseServer(503, "{\"error\":\"busy\"}")) {
            SeedanceServiceImpl service = new SeedanceServiceImpl(config(server.url()), new ObjectMapper());

            Exception error = assertThrows(Exception.class,
                    () -> service.generate(List.of(), "prompt", 5, "16:9", null));
            assertFalse(error instanceof SubmissionNotAcceptedException);
        }
    }

    @Test
    void generateFailsFastWhenRemoteHangs() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0)) {
            // 黑洞服务端：接受连接后既不读也不写，保持挂起
            Thread blackhole = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        Socket s = server.accept();
                        // 故意不处理，连接保持打开直到测试结束
                    } catch (Exception ignored) {
                        return;
                    }
                }
            });
            blackhole.setDaemon(true);
            blackhole.start();

            SeedanceConfig config = new SeedanceConfig();
            config.setUrl("http://127.0.0.1:" + server.getLocalPort());
            config.setApiKey("test-key");
            config.setModel("test-model");
            config.setConnectTimeoutMs(1000);
            config.setReadTimeoutMs(1500);

            SeedanceServiceImpl service = new SeedanceServiceImpl(config, new ObjectMapper());

            // 读超时 1.5s，留足余量断言 8s 内必然抛错；未设超时时这里会挂到 assertTimeoutPreemptively 兜底杀线程
            assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                    assertThrows(Exception.class, () ->
                            service.generate(List.of(), "test prompt", 5, "16:9", null)));

            stop.set(true);
        }
    }

    private SeedanceConfig config(String url) {
        SeedanceConfig config = new SeedanceConfig();
        config.setUrl(url);
        config.setApiKey("test-key");
        config.setModel("test-model");
        config.setConnectTimeoutMs(1000);
        config.setReadTimeoutMs(1500);
        return config;
    }

    private static final class TestResponseServer implements AutoCloseable {
        private final HttpServer server;

        private TestResponseServer(int status, String body) throws Exception {
            server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
