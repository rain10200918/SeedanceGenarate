package org.example.seedancegenarate.config;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.interceptor.AdminRoleInterceptor;
import org.example.seedancegenarate.interceptor.ApiKeyInterceptor;
import org.example.seedancegenarate.interceptor.ApiKeyRateLimitInterceptor;
import org.example.seedancegenarate.interceptor.AuthInterceptor;
import org.example.seedancegenarate.interceptor.PromptOptimizeRateLimitInterceptor;
import org.example.seedancegenarate.interceptor.RateLimitInterceptor;
import org.example.seedancegenarate.interceptor.RegisterRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final AdminRoleInterceptor adminRoleInterceptor;
    private final ApiKeyInterceptor apiKeyInterceptor;
    private final ApiKeyRateLimitInterceptor apiKeyRateLimitInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final RegisterRateLimitInterceptor registerRateLimitInterceptor;
    private final PromptOptimizeRateLimitInterceptor promptOptimizeRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(registerRateLimitInterceptor)
                .addPathPatterns("/api/auth/register");

        // 对外 API：走 API Key 鉴权，不走登录 token；限流在其后（依赖注入的 api_key 属性）
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/**");
        registry.addInterceptor(apiKeyRateLimitInterceptor)
                .addPathPatterns("/api/v1/videos");

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/v1/**",
                        "/api/callback/**",
                        // 支付渠道回调：来源是支付宝服务器，没有登录 token，靠 RSA2 验签鉴权
                        "/api/notify/**"
                );

        // 管理接口角色强制：必须注册在 authInterceptor 之后（按注册顺序执行，UserContext 已填充）。
        // 保护清单唯一来源是 AdminPaths，架构测试用同一常量校验覆盖完整性。
        registry.addInterceptor(adminRoleInterceptor)
                .addPathPatterns(AdminPaths.PROTECTED_PREFIXES.stream()
                        .map(prefix -> prefix + "/**")
                        .toList());

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/video/image2video",
                        "/api/video/text2video"
                );

        registry.addInterceptor(promptOptimizeRateLimitInterceptor)
                .addPathPatterns("/api/video/optimize-prompt");
    }
}
