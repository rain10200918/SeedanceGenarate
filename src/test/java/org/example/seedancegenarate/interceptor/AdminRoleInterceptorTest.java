package org.example.seedancegenarate.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor(new ObjectMapper());

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static AppUser userWithRole(String role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setRole(role);
        return user;
    }

    @Test
    void rejectsNonAdminWith403() throws Exception {
        // 测什么：普通用户（USER 角色）访问管理路径在拦截器层被拒
        // 怎么算红：preHandle 返回 true（放行）或状态码不是 403，说明角色强制层失效
        UserContext.setUser(userWithRole("USER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(get("/api/admin/users"), response, new Object());

        assertFalse(pass);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("无权限"));
    }

    @Test
    void allowsAdmin() throws Exception {
        // 测什么：ADMIN 角色（含小写 admin，复用 UserContext 的大小写不敏感判断）正常放行
        // 怎么算红：管理员被 403，管理后台全体不可用
        UserContext.setUser(userWithRole("admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(get("/api/admin/users"), response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMissingUserWith401InsteadOfPassingThrough() throws Exception {
        // 测什么：UserContext 为空（admin 路径被误加进 auth 排除清单的退化场景）必须 401，不许放行
        // 怎么算红：preHandle 返回 true —— 意味着鉴权链断裂时管理接口直接裸奔
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(get("/api/admin/users"), response, new Object());

        assertFalse(pass);
        assertEquals(401, response.getStatus());
    }

    @Test
    void allowsOptionsPreflightWithoutUser() throws Exception {
        // 测什么：CORS 预检（OPTIONS）不带用户上下文也放行，对齐 AuthInterceptor 行为
        // 怎么算红：OPTIONS 被 401/403，浏览器跨域预检失败，管理后台所有请求发不出去
        MockHttpServletRequest request = get("/api/admin/users");
        request.setMethod("OPTIONS");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI(uri);
        return request;
    }
}
