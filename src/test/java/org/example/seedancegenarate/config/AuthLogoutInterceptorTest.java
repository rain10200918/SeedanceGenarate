package org.example.seedancegenarate.config;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.interceptor.AdminRoleInterceptor;
import org.example.seedancegenarate.interceptor.ApiKeyInterceptor;
import org.example.seedancegenarate.interceptor.ApiKeyRateLimitInterceptor;
import org.example.seedancegenarate.interceptor.AuthInterceptor;
import org.example.seedancegenarate.interceptor.PromptOptimizeRateLimitInterceptor;
import org.example.seedancegenarate.interceptor.RateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AuthLogoutInterceptorTest {

    @Test
    void logoutBypassesAuthInterceptorWhileProtectedEndpointsStillMatch() {
        // 【测什么】/logout 不进 AuthInterceptor，因而不会先续期 token、查用户 SQL 或写活动 SQL。
        // 【怎么算红】从 WebConfig 的 auth 排除清单删掉 /api/auth/logout，这条必须变红。
        AuthInterceptor auth = mock(AuthInterceptor.class);
        WebConfig config = new WebConfig(
                auth,
                mock(AdminRoleInterceptor.class),
                mock(ApiKeyInterceptor.class),
                mock(ApiKeyRateLimitInterceptor.class),
                mock(RateLimitInterceptor.class),
                mock(PromptOptimizeRateLimitInterceptor.class)
        );
        InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
        config.addInterceptors(registry);
        MappedInterceptor authMapping = registry.mappedFor(auth);

        assertFalse(authMapping.matches(request("POST", "/api/auth/logout")));
        assertFalse(authMapping.matches(request("POST", "/api/auth/register/email-code")));
        assertFalse(authMapping.matches(request("POST", "/api/auth/register/email-code/resend")));
        assertTrue(authMapping.matches(request("GET", "/api/auth/me")));
    }

    private static HttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }

    private static final class InspectableInterceptorRegistry extends InterceptorRegistry {
        MappedInterceptor mappedFor(AuthInterceptor interceptor) {
            List<Object> registrations = getInterceptors();
            return registrations.stream()
                    .filter(MappedInterceptor.class::isInstance)
                    .map(MappedInterceptor.class::cast)
                    .filter(mapped -> mapped.getInterceptor() == interceptor)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
