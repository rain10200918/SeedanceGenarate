package org.example.seedancegenarate.config;

import java.util.List;

/**
 * 管理接口保护前缀的唯一清单。
 * <p>
 * {@link WebConfig} 用它注册 AdminRoleInterceptor，架构测试
 * {@code AdminAuthArchitectureTest} 用同一个常量断言全部管理 controller 被覆盖——
 * 注册与校验共享一份数据，清单漂移在编译/测试期暴露，而不是上线后裸奔。
 * <p>
 * 新增管理 controller 时：路径挂到下列前缀之下即可自动受保护；
 * 如需新前缀，必须同时加进这里（否则架构测试红）。
 */
public final class AdminPaths {

    /** 受 ADMIN 角色强制保护的路径前缀（拦截时追加 /** 通配） */
    public static final List<String> PROTECTED_PREFIXES = List.of(
            "/api/admin",
            "/api/invite-codes"
    );

    private AdminPaths() {
    }
}
