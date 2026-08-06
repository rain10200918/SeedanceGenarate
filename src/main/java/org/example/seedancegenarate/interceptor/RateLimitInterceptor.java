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

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {
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
                "generate:user:" + userId,
                UserContext.isAdmin() ? rateLimitConfig.getGenerateAdmin() : rateLimitConfig.getGenerateUser()
        );
        boolean ipAllowed = tokenBucketRateLimitService.tryAcquire(
                "generate:ip:" + ip,
                rateLimitConfig.getGenerateIp()
        );
        if (userAllowed && ipAllowed) {
            return true;
        }
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.tooManyRequests("请求过于频繁，请稍后再试")));
        return false;
    }
}
