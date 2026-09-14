package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.service.OssService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiGenerationQuoteTest {

    @Test
    void rejectsUnsafeDurationBeforePricing() {
        // 【测什么】非法时长不得进入计价，避免产生负数金额或极端数值。
        ApiVideoServiceImpl service = new ApiVideoServiceImpl(null, null, null, null, null);

        ApiException error = assertThrows(ApiException.class, () -> service.quote("test-model", 0));

        assertEquals("VALIDATION_ERROR", error.getCode());
        // 【怎么算红】如果校验发生在计价之后，这里会因 null 依赖或返回非法报价而失败。
    }

    @Test
    void quoteReusesSubmitEstimateWithoutWrites() {
        // 【测什么】报价从全局模型找到 provider，且只调用真实提交共用的 estimate。
        ModelSpec spec = new ModelSpec("test-provider", "test-model", "测试模型", false,
                0, 1, List.of("16:9"), 5, 10, List.of(5), OutputType.VIDEO);
        VideoEngine engine = proxy(VideoEngine.class, (method, args) -> switch (method) {
            case "provider" -> "test-provider";
            case "models" -> List.of(spec);
            default -> throw new AssertionError("报价不应触发引擎方法: " + method);
        });
        List<String> calls = new ArrayList<>();
        VideoSubmitService.PriceEstimate expected = new VideoSubmitService.PriceEstimate(
                "test-provider", "test-model", 5, "VIDEO",
                new BigDecimal("0.20"), new BigDecimal("1.00"), "CNY");
        VideoSubmitService submit = proxy(VideoSubmitService.class, (method, args) -> {
            calls.add(method);
            if ("estimate".equals(method)) {
                assertEquals("test-provider", args[0]);
                assertEquals("test-model", args[1]);
                assertEquals(5, args[2]);
                return expected;
            }
            throw new AssertionError("报价不应调用提交或其他服务: " + method);
        });
        ApiVideoServiceImpl service = new ApiVideoServiceImpl(
                failOnCall(ApiCallLogMapper.class), submit, new VideoEngineRegistry(List.of(engine)),
                null, failOnCall(VideoTaskService.class));

        VideoSubmitService.PriceEstimate actual = service.quote(" test-model ", 5);

        assertSame(expected, actual);
        assertEquals(List.of("estimate"), calls);
        // 【怎么算红】任何任务、调用日志、OSS 写入或 submit 都表明报价产生了副作用。
    }

    private <T> T failOnCall(Class<T> type) {
        return proxy(type, (method, args) -> {
            throw new AssertionError("报价不应调用 " + type.getSimpleName() + "." + method);
        });
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
