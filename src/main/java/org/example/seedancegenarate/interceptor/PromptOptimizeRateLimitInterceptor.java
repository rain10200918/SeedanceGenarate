package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.example.seedancegenarate.service.RateLimitResult;
import org.example.seedancegenarate.service.TokenBucketRateLimitService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 提示词优化接口限流：按用户 + IP 双维度，避免共享大模型被刷。
 */
@Component
@RequiredArgsConstructor
public class PromptOptimizeRateLimitInterceptor implements HandlerInterceptor {
    private final TokenBucketRateLimitService tokenBucketRateLimitService;
    private final RateLimitConfig rateLimitConfig;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Long userId = UserContext.requireUserId();
        String ip = IpUtils.getClientIp(request);
        boolean publicApi = request.getRequestURI().startsWith("/api/v1/");
        RateLimitResult userResult;
        RateLimitResult ipResult;
        try {
            // 对外 API 可以多实例扩容，必须强制用 Redis 共享桶；UI 保留本地开发回退。
            userResult = acquire("prompt:user:" + userId, rateLimitConfig.getPromptOptimizeUser(), publicApi);
            ipResult = acquire("prompt:ip:" + ip, rateLimitConfig.getPromptOptimizeIp(), publicApi);
        } catch (RuntimeException e) {
            if (!publicApi) {
                throw e;
            }
            writeApiError(response, request, 503, "RATE_LIMIT_UNAVAILABLE",
                    "限流服务暂不可用，请稍后重试");
            return false;
        }
        if (userResult.allowed() && ipResult.allowed()) {
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(Math.max(
                userResult.allowed() ? 0 : userResult.retryAfterSeconds(),
                ipResult.allowed() ? 0 : ipResult.retryAfterSeconds())));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Object body = publicApi
                ? new ApiErrorResponse(new ApiErrorResponse.ApiError(
                        "RATE_LIMITED", "优化过于频繁，请稍后再试",
                        ApiExceptionHandler.requestId(request)))
                : Result.tooManyRequests("优化过于频繁，请稍后再试");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    private RateLimitResult acquire(String key, RateLimitConfig.Bucket bucket, boolean distributed) {
        return distributed
                ? tokenBucketRateLimitService.tryAcquireDistributed(key, bucket)
                : tokenBucketRateLimitService.tryAcquire(key, bucket);
    }

    private void writeApiError(HttpServletResponse response, HttpServletRequest request,
                               int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ApiErrorResponse(new ApiErrorResponse.ApiError(
                        code, message, ApiExceptionHandler.requestId(request)))));
    }
}
