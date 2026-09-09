package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiPromptOptimizeRequest;
import org.example.seedancegenarate.dto.ApiPromptOptimizeResponse;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.ApiVideoService;
import org.example.seedancegenarate.service.PromptContext;
import org.example.seedancegenarate.service.PromptOptimizeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ApiPromptControllerTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void optimizesWithValidatedModelAndGenerationContext() {
        // 【测什么】对外提示词入口使用 API Key 属主，先校验模型，再完整传递生成上下文。
        AppUser owner = new AppUser();
        owner.setId(77L);
        UserContext.setUser(owner);
        AtomicReference<PromptContext> capturedContext = new AtomicReference<>();
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AtomicInteger modelChecks = new AtomicInteger();

        ApiVideoService videos = proxy(ApiVideoService.class, (method, args) -> {
            if ("validateModel".equals(method)) {
                modelChecks.incrementAndGet();
                assertEquals(" test-model ", args[0]);
                return new ApiVideoService.ModelTarget("provider", "test-model");
            }
            throw new AssertionError("提示词优化不应调用视频服务的其他方法: " + method);
        });
        PromptOptimizeService optimizer = proxy(PromptOptimizeService.class, (method, args) -> {
            if ("optimize".equals(method)) {
                capturedPrompt.set((String) args[0]);
                capturedContext.set((PromptContext) args[1]);
                return "optimized scene";
            }
            throw new AssertionError("对外接口不应指定 LLM 管理通道");
        });

        ApiPromptOptimizeResponse result = new ApiPromptController(optimizer, videos).optimize(
                new ApiPromptOptimizeRequest("  original scene  ", " test-model ",
                        2, 1, 0, 6, " 16:9 "));

        assertEquals("original scene", result.originalPrompt());
        assertEquals("optimized scene", result.optimizedPrompt());
        assertEquals("test-model", result.model());
        assertEquals("original scene", capturedPrompt.get());
        assertEquals(new PromptContext("test-model", 2, 1, 0, 6, "16:9"), capturedContext.get());
        assertEquals(1, modelChecks.get());
        // 【怎么算红】未校验显式模型、丢参数或回传未规范化模型，Agent 就可能得到错模型的提示词。
    }

    @Test
    void rejectsOversizedPromptBeforeCallingDependencies() {
        // 【测什么】超过 5000 字符的 prompt 在调用 LLM 和模型服务前被拒绝。
        AppUser owner = new AppUser();
        owner.setId(77L);
        UserContext.setUser(owner);
        AtomicInteger calls = new AtomicInteger();
        ApiVideoService videos = proxy(ApiVideoService.class, (method, args) -> {
            calls.incrementAndGet();
            return null;
        });
        PromptOptimizeService optimizer = proxy(PromptOptimizeService.class, (method, args) -> {
            calls.incrementAndGet();
            return null;
        });

        ApiException error = assertThrows(ApiException.class,
                () -> new ApiPromptController(optimizer, videos).optimize(
                        new ApiPromptOptimizeRequest("x".repeat(5001), "test-model",
                                0, 0, 0, 5, "16:9")));

        assertEquals("VALIDATION_ERROR", error.getCode());
        // 【怎么算红】校验过晚导致任何下游调用，会白白消耗 LLM 成本。
        assertEquals(0, calls.get());
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    return invocation.call(method.getName(), args == null ? new Object[0] : args);
                });
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args) throws Throwable;
    }
}
