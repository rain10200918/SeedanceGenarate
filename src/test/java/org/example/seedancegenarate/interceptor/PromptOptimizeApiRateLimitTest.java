package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptOptimizeApiRateLimitTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void publicApiUsesDistributedOwnerAndIpBucketsAndStableErrorShape() throws Exception {
        // 【测什么】多实例下对外优化接口强制消费 Redis 中的属主 + IP 共享桶。
        AppUser owner = new AppUser();
        owner.setId(88L);
        UserContext.setUser(owner);
        List<String> methods = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        TokenBucketRateLimitService limiter = (TokenBucketRateLimitService) Proxy.newProxyInstance(
                TokenBucketRateLimitService.class.getClassLoader(),
                new Class<?>[]{TokenBucketRateLimitService.class},
                (proxy, method, args) -> {
                    methods.add(method.getName());
                    keys.add((String) args[0]);
                    return keys.size() == 1 ? RateLimitResult.permitted() : RateLimitResult.rejected(7);
                });
        ObjectMapper mapper = new ObjectMapper();
        PromptOptimizeRateLimitInterceptor interceptor = new PromptOptimizeRateLimitInterceptor(
                limiter, new RateLimitConfig(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/prompts/optimize");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Idempotency-Key", "req-rate-test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(List.of("tryAcquireDistributed", "tryAcquireDistributed"), methods);
        assertEquals(List.of("prompt:user:88", "prompt:ip:203.0.113.10"), keys);
        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeader("Retry-After"));
        JsonNode json = mapper.readTree(response.getContentAsString());
        assertEquals("RATE_LIMITED", json.at("/error/code").asText());
        assertEquals("req-rate-test", json.at("/error/requestId").asText());
        // 【怎么算红】任一维度落到本机桶，或返回 UI Result 结构，都会让分布式限流或 API 契约失效。
    }
}
