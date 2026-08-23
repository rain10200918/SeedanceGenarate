package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理接口的角色强制层（结构性保证，覆盖 {@link org.example.seedancegenarate.config.AdminPaths} 清单路径）。
 * <p>
 * 必须注册在 {@link AuthInterceptor} 之后（Spring 按注册顺序执行 preHandle），
 * 届时 UserContext 已填充。controller 方法内既有的 requireAdmin 保留为纵深防御：
 * 本拦截器挡「忘写守卫」的结构风险，方法内守卫挡「路径没挂进清单」的残余风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminRoleInterceptor implements HandlerInterceptor {
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        AppUser user = UserContext.getUser();
        if (user == null) {
            // 正常链路 AuthInterceptor 已拦截未登录；此分支防「admin 路径被误加进 auth 排除清单」
            // 时退化为放行——宁可 401 也不裸奔。
            write(response, HttpServletResponse.SC_UNAUTHORIZED, Result.unauthorized("请先登录"));
            return false;
        }
        if (!UserContext.isAdmin()) {
            log.warn("非管理员访问管理接口被拒: userId={}, uri={}", user.getId(), request.getRequestURI());
            write(response, HttpServletResponse.SC_FORBIDDEN, Result.fail(403, "无权限访问，仅管理员可操作"));
            return false;
        }
        return true;
    }

    private void write(HttpServletResponse response, int status, Result<?> body) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
