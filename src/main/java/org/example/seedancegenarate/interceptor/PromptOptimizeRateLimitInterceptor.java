package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Result;
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
        boolean userAllowed = tokenBucketRateLimitService.tryAcquire(
                "optimize:user:" + userId,
                rateLimitConfig.getPromptOptimizeUser()
        );
        boolean ipAllowed = tokenBucketRateLimitService.tryAcquire(
                "optimize:ip:" + ip,
                rateLimitConfig.getPromptOptimizeIp()
        );
        if (userAllowed && ipAllowed) {
            return true;
        }
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.tooManyRequests("优化过于频繁，请稍后再试")));
        return false;
    }
}
