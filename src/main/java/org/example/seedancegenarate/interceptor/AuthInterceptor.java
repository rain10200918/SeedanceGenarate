package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.UserActivityService;
import org.example.seedancegenarate.service.UserTokenService;
import org.example.seedancegenarate.util.TokenUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final UserTokenService userTokenService;
    private final UserActivityService userActivityService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = TokenUtils.resolveToken(request);
        AppUser user = userTokenService.getUserByToken(token);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.unauthorized("请先登录")));
            return false;
        }
        UserContext.setUser(user);
        userActivityService.recordOperation(user.getId(), resolveOperation(request), request);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveOperation(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if ("GET".equalsIgnoreCase(method) && "/api/auth/me".equals(uri)) {
            return "查看用户信息";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/video/image2video".equals(uri)) {
            return "提交视频生成";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/video/text2video".equals(uri)) {
            return "提交文生视频";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/video/optimize-prompt".equals(uri)) {
            return "优化提示词";
        }
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/video/download/")) {
            return "下载视频";
        }
        if ("GET".equalsIgnoreCase(method) && "/api/video/tasks".equals(uri)) {
            return "查看任务列表";
        }
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/video/task/")) {
            return "刷新任务状态";
        }
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/video/")) {
            return "播放生成视频";
        }
        return method + " " + uri;
    }
}
