package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 对外 API 按钥匙限流（令牌桶，仅限提交类请求）。429 带 Retry-After 头，
 * 错误按统一契约输出。须在 ApiKeyInterceptor 之后执行（依赖注入的 api_key 属性）。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyRateLimitInterceptor implements HandlerInterceptor {
    private final TokenBucketRateLimitService tokenBucketRateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Object attribute = request.getAttribute("api_key");
        if (!(attribute instanceof ApiKey apiKey)) {
            return true; // 鉴权失败由 ApiKeyInterceptor 处理
        }
        boolean allowed = tokenBucketRateLimitService.tryAcquire(
                "api-key:" + apiKey.getId(), rateLimitConfig.getApiKey());
        if (allowed) {
            return true;
        }
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "30");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ApiErrorResponse(new ApiErrorResponse.ApiError(
                        ApiException.rateLimited().getCode(), "请求过于频繁，请稍后再试",
                        ApiExceptionHandler.requestId(request)))));
        return false;
    }
}
