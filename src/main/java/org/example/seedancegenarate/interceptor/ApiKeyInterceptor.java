package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.example.seedancegenarate.service.ApiKeyService;
import org.example.seedancegenarate.service.AppUserService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 对外 API 鉴权（/api/v1/**）：解析 {@code Authorization: Bearer sk-...} → 哈希比对 → 校验状态/过期
 * → 把属主用户注入 {@link UserContext}（下游 tasks 隔离 / 计费 / 模型开关零改动复用）。
 * 认证失败按统一错误契约输出，并带请求追踪号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {
    private final ApiKeyService apiKeyService;
    private final AppUserService appUserService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            String plainKey = resolveBearer(request);
            ApiKey apiKey = apiKeyService.resolveAndValidate(plainKey);
            AppUser owner = appUserService.getById(apiKey.getUserId());
            if (owner == null) {
                throw ApiException.invalidApiKey();
            }
            UserContext.setUser(owner);
            // 供 controller / 限流拦截器使用
            request.setAttribute("api_key", apiKey);
            // 记录最后使用时间（轻量更新，失败不影响请求）
            try {
                apiKeyService.markUsed(apiKey);
            } catch (Exception e) {
                log.warn("更新 API Key 最后使用时间失败: {}", e.getMessage());
            }
            return true;
        } catch (ApiException e) {
            writeError(response, request, e);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveBearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        throw ApiException.invalidApiKey();
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, ApiException exception) throws Exception {
        response.setStatus(exception.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        if (exception.getHttpStatus() == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS) {
            response.setHeader("Retry-After", "30");
        }
        String requestId = ApiExceptionHandler.requestId(request);
        response.getWriter().write(objectMapper.writeValueAsString(
                new ApiErrorResponse(new ApiErrorResponse.ApiError(
                        exception.getCode(), exception.getMessage(), requestId))));
    }
}
