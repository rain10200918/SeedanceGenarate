package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.controller.AuthController;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.interceptor.AuthInterceptor;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.TokenCacheService;
import org.example.seedancegenarate.service.UserActivityService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 可选本地 Redis 集成测试，默认跳过。 */
class RedisTokenHashIntegrationTest {

    @Test
    void storesHashValidatesRefreshesAndDeletesToken() throws Exception {
        // 【测什么】真实 Redis 中并发重复登出只删除当前 token，另一设备 token 仍有效，后续鉴权返回 401。
        // 【怎么算红】删掉 DEL、改成按用户批删，或让 AuthInterceptor 在 Redis miss 后继续放行，这条必须变红。
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_REDIS_INTEGRATION_TESTS")));
        String password = System.getenv("SPRING_REDIS_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank());
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_INTEGRATION_PORT", "6379"));

        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.setPassword(password);
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            AuthTokenProperties properties = new AuthTokenProperties();
            properties.setKeyPrefix("test:seedance:auth:" + UUID.randomUUID());
            RedisTokenCacheService service = new RedisTokenCacheService(redis, properties);

            assertTrue(service.put("token", 42L, Instant.now().plusSeconds(60), 60));
            TokenCacheService.CachedToken cached = service.getAndRefreshIfNeeded("token", 60, 300);
            assertNotNull(cached);
            assertEquals(42L, cached.userId());
            Long refreshedTtl = redis.getExpire(properties.getKeyPrefix() + ":token");
            assertNotNull(refreshedTtl);
            assertTrue(refreshedTtl > 50 && refreshedTtl <= 60);

            assertTrue(service.put("other-device-token", 42L, Instant.now().plusSeconds(60), 60));
            AppUserService users = mock(AppUserService.class);
            AppUser user = new AppUser();
            user.setId(42L);
            when(users.getById(42L)).thenReturn(user);
            UserTokenServiceImpl tokens = new UserTokenServiceImpl(users, service, properties);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        tokens.deleteToken("token");
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }

            assertNull(service.getAndRefreshIfNeeded("token", 60, 300));
            assertNotNull(service.getAndRefreshIfNeeded("other-device-token", 60, 300));
            verifyNoInteractions(users);

            UserActivityService activity = mock(UserActivityService.class);
            AuthInterceptor interceptor = new AuthInterceptor(tokens, activity, new ObjectMapper());
            MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/api/auth/me");
            protectedRequest.addHeader("Authorization", "Bearer token");
            MockHttpServletResponse protectedResponse = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(protectedRequest, protectedResponse, new Object()));
            assertEquals(401, protectedResponse.getStatus());
            verifyNoInteractions(users, activity);

            clearInvocations(users);
            CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
            AuthController controller = new AuthController(
                    users,
                    tokens,
                    captcha,
                    mock(org.example.seedancegenarate.service.RegistrationEmailSessionService.class)
            );
            MockHttpServletRequest logoutRequest = new MockHttpServletRequest("POST", "/api/auth/logout");
            logoutRequest.addHeader("Authorization", "Bearer already-gone");
            assertTrue(controller.logout(logoutRequest).getData());
            verifyNoInteractions(users, captcha);
        } finally {
            factory.destroy();
        }
    }
}
