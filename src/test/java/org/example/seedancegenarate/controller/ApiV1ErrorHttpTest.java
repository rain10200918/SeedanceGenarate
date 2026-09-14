package org.example.seedancegenarate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

import java.io.IOException;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiV1ErrorHttpTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Fixture.class);
        context.register(org.example.seedancegenarate.config.ApiV1ExceptionConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach void tearDown() { context.close(); }

    private void error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        mvc.perform(request.header("Idempotency-Key", "req_error_test"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.requestId").value("req_error_test"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // 【测什么】MVC真实绑定异常返回HTTP400和API错误体。
    // 【怎么算红】缺少v1优先解析器时畸形JSON和参数进入UI Result，状态或error字段失败。
    @Test void malformedJsonAndParametersAre400() throws Exception {
        error(post("/api/v1/probe").contentType("application/json").content("{"), 400, "VALIDATION_ERROR");
        error(get("/api/v1/number").param("count", "no"), 400, "VALIDATION_ERROR");
        error(get("/api/v1/number"), 400, "VALIDATION_ERROR");
    }

    // 【测什么】未命中handler和静态资源缺失均404，方法405及媒体415保留协议状态。
    // 【怎么算红】按controller选择advice漏掉null handler时404形状失败；吞方法异常时405失败。
    @Test void routingFailuresDoNotNeedAControllerHandler() throws Exception {
        error(get("/api/v1/missing"), 404, "NOT_FOUND");
        error(get("/api/v1/static/missing.png"), 404, "NOT_FOUND");
        error(get("/api/v1/probe"), 405, "METHOD_NOT_ALLOWED");
        mvc.perform(get("/api/v1/probe")).andExpect(header().string("Allow", "POST"));
        error(post("/api/v1/probe").contentType("text/plain").content("x"), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    // 【测什么】拦截器及controller未知故障输出安全500，不泄漏原始诊断。
    // 【怎么算红】继续使用GlobalExceptionHandler或直传异常消息时状态/secret断言失败。
    @Test void interceptorAndControllerFailuresAreSafe500() throws Exception {
        for (String path : new String[]{"/api/v1/intercept", "/api/v1/crash", "/api/v1/internal"}) {
            error(get(path), 500, "INTERNAL_ERROR");
            mvc.perform(get(path)).andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("secret"))));
        }
    }

    // 【测什么】已有ApiExceptionHandler standalone仍能安全返回供应商503并保留429退避头。
    // 【怎么算红】advice绕过共享安全分类直接使用消息时泄漏secret。
    @Test void standaloneAdviceRemainsSafeAndCompatible() throws Exception {
        MockMvc standalone = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
        standalone.perform(get("/api/v1/provider"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        standalone.perform(get("/api/v1/limited"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "30"));
    }

    // 【测什么】UI仍是原Result/HTTP200，v1已提交下载响应不被重写。
    // 【怎么算红】取消路径或committed守卫时UI变500或已提交正文被追加错误JSON。
    @Test void uiAndCommittedResponseArePreserved() throws Exception {
        mvc.perform(get("/ui/crash")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists()).andExpect(jsonPath("$.error").doesNotExist());
        mvc.perform(get("/api/v1/committed")).andExpect(status().isOk())
                .andExpect(content().string("already-sent"));
    }

    // 【测什么】超长和控制字符幂等头不能回显，64字符key两侧空格按trim契约保持。
    // 【怎么算红】按原header长度判64、返回原header或每次生成不同id，边界/稳定性断言失败。
    @Test void requestIdsAreBoundedSafeAndStable() throws Exception {
        for (String header : new String[]{"x".repeat(65), "bad\r\nsecret", "bad\u0000secret", "bad\u2028secret"}) {
            mvc.perform(get("/api/v1/missing").header("Idempotency-Key", header))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.requestId").value(org.hamcrest.Matchers.matchesPattern("req_[a-f0-9]{32}")));
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader("Idempotency-Key", header);
            org.junit.jupiter.api.Assertions.assertEquals(ApiExceptionHandler.requestId(request), ApiExceptionHandler.requestId(request));
        }
        mvc.perform(get("/api/v1/missing").header("Idempotency-Key", "x".repeat(64)))
                .andExpect(jsonPath("$.error.requestId").value("x".repeat(64)));
        mvc.perform(get("/api/v1/missing").header("Idempotency-Key", "  " + "x".repeat(64) + "  "))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.requestId").value("x".repeat(64)));
    }

    // 【测什么】实际API Key拦截器的直接写入路径也对类型化503安全处理。
    // 【怎么算红】writeError直接信任ApiException.message时泄漏secret。
    @Test void actualApiKeyInterceptorDoesNotLeakTypedFailure() throws Exception {
        var keys = org.mockito.Mockito.mock(org.example.seedancegenarate.service.ApiKeyService.class);
        org.mockito.Mockito.when(keys.resolveAndValidate("test")).thenThrow(ApiException.providerUnavailable("https://internal?token=secret"));
        var interceptor = new org.example.seedancegenarate.interceptor.ApiKeyInterceptor(keys,
                org.mockito.Mockito.mock(org.example.seedancegenarate.service.AppUserService.class), new ObjectMapper());
        MockMvc keyed = MockMvcBuilders.standaloneSetup(new Probe()).addInterceptors(interceptor).build();
        keyed.perform(get("/api/v1/number").param("count", "1").header("Authorization", "Bearer test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        response.getWriter().write("already-sent");
        response.flushBuffer();
        interceptor.preHandle(request, response, new Object());
        org.junit.jupiter.api.Assertions.assertEquals("already-sent", response.getContentAsString());
    }

    // 【测什么】下载选择了输出流但尚未提交时，失败清除残留文件字节并返回安全500。
    // 【怎么算红】仅用getWriter或不resetBuffer时无法写JSON或JSON前残留文件字节。
    @Test void uncommittedBinaryResponseBecomesJson() throws Exception {
        error(get("/api/v1/binary-failure"), 500, "INTERNAL_ERROR");
        mvc.perform(get("/api/v1/binary-failure"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("partial-file"))));
    }

    @Configuration @EnableWebMvc
    static class Fixture implements WebMvcConfigurer {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Probe probe() { return new Probe(); }
        @Bean ApiExceptionHandler apiAdvice() { return new ApiExceptionHandler(); }
        @Bean GlobalExceptionHandler uiAdvice() { return new GlobalExceptionHandler(); }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/api/v1/static/**").addResourceLocations("classpath:/missing-test-resources/");
        }
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    throw new RuntimeException("https://internal?token=secret SELECT password");
                }
            }).addPathPatterns("/api/v1/intercept");
        }
    }

    @RestController
    static class Probe {
        @PostMapping(value = "/api/v1/probe", consumes = "application/json")
        Map<String, Object> probe(@RequestBody Map<String, Object> body) { return body; }
        @GetMapping("/api/v1/number") int number(@RequestParam("count") int count) { return count; }
        @GetMapping({"/api/v1/crash", "/api/v1/intercept", "/ui/crash"})
        void crash() { throw new RuntimeException("https://internal?token=secret SELECT password"); }
        @GetMapping("/api/v1/internal") void internal() { throw ApiException.internal("token=secret"); }
        @GetMapping("/api/v1/provider") void provider() { throw ApiException.providerUnavailable("https://internal?token=secret"); }
        @GetMapping("/api/v1/limited") void limited() { throw ApiException.rateLimited(); }
        @GetMapping("/api/v1/binary-failure") void binaryFailure(HttpServletResponse response) throws IOException {
            response.setContentType("video/mp4");
            response.setHeader("Content-Disposition", "attachment; filename=partial.mp4");
            response.getOutputStream().write("partial-file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new IOException("https://internal?token=secret");
        }
        @GetMapping("/api/v1/committed") void committed(HttpServletResponse response) throws IOException {
            response.getWriter().write("already-sent");
            response.flushBuffer();
            throw ApiException.internal("token=secret");
        }
    }
}
