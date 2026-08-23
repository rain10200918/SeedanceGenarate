package org.example.seedancegenarate.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构守卫：管理接口必须落在 {@link AdminPaths#PROTECTED_PREFIXES} 之下。
 * <p>
 * 与 WebConfig 共享同一个常量，因此「注册清单」与「本测试断言的清单」物理上不可能漂移；
 * 会漂移的只剩 controller 本身，这正是下面三条规则盯住的。
 * <p>
 * 已知限制：类名不含 Admin、也不调用任何守卫的未来管理 controller 对静态规则不可见
 * （详见 .my-loop/CURRENT-admin-auth.md 已知限制一节）。
 */
class AdminAuthArchitectureTest {

    private static final Path CONTROLLER_DIR =
            Path.of("src/main/java/org/example/seedancegenarate/controller");
    private static final Pattern REQUEST_MAPPING =
            Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");

    private record ControllerInfo(String name, String prefix, String source) {
    }

    private static List<ControllerInfo> scanControllers() throws IOException {
        List<ControllerInfo> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(CONTROLLER_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith("Controller.java")).toList()) {
                String src = Files.readString(p);
                Matcher m = REQUEST_MAPPING.matcher(src);
                String prefix = m.find() ? m.group(1) : "";
                result.add(new ControllerInfo(
                        p.getFileName().toString().replace(".java", ""), prefix, src));
            }
        }
        return result;
    }

    private static boolean underProtectedPrefix(String prefix) {
        return AdminPaths.PROTECTED_PREFIXES.stream()
                .anyMatch(p -> prefix.equals(p) || prefix.startsWith(p + "/"));
    }

    @Test
    void adminNamedControllersMustBeUnderProtectedPrefixes() throws IOException {
        // 测什么：类名含 Admin 的 controller（Admin*Controller / *AdminController）路径必须在保护清单下
        // 怎么算红：新增 AdminXxxController 挂了 /api/xxx 这类未保护前缀 —— 说明有管理接口绕开了拦截器
        List<String> violations = new ArrayList<>();
        for (ControllerInfo c : scanControllers()) {
            if (c.name().contains("Admin") && !underProtectedPrefix(c.prefix())) {
                violations.add(c.name() + " -> " + c.prefix());
            }
        }
        assertTrue(violations.isEmpty(),
                "管理命名的 controller 不在 AdminPaths 保护前缀下（拦截器不会拦它）: " + violations);
    }

    @Test
    void guardCallingControllersMustBeUnderProtectedPrefixes() throws IOException {
        // 测什么：使用 requireAdmin 门禁（非管理员即抛错）的 controller，路径必须在保护清单下
        // 怎么算红：某个不叫 Admin 但明显是管理语义的 controller（如 InviteCodeController）
        //          没被拦截器覆盖 —— 它只剩方法内守卫这一层，忘写一处就是裸奔
        // 注意：只认「门禁」语义（requireAdmin）。裸 isAdmin() 条件是「数据范围」语义
        //      （管理员看全量、用户看自己的，如 VideoController /options、/tasks），
        //      这类混合受众接口绝不能整体锁成 admin-only。
        List<String> violations = new ArrayList<>();
        for (ControllerInfo c : scanControllers()) {
            if (c.source().contains("requireAdmin") && !underProtectedPrefix(c.prefix())) {
                violations.add(c.name() + " -> " + c.prefix());
            }
        }
        assertTrue(violations.isEmpty(),
                "使用 requireAdmin 门禁的 controller 不在 AdminPaths 保护前缀下: " + violations);
    }

    @Test
    void everyProtectedPrefixMustMatchARealController() throws IOException {
        // 测什么：AdminPaths 清单里每个前缀都有真实 controller 挂载（反向校验）
        // 怎么算红：清单里出现拼写错误的前缀（如 /api/admins）—— 注册了个空拦截，
        //          而真实接口在保护范围外，正向规则可能因命名不含 Admin 而漏报
        List<ControllerInfo> controllers = scanControllers();
        List<String> orphans = new ArrayList<>();
        for (String prefix : AdminPaths.PROTECTED_PREFIXES) {
            boolean matched = controllers.stream()
                    .anyMatch(c -> c.prefix().equals(prefix) || c.prefix().startsWith(prefix + "/"));
            if (!matched) {
                orphans.add(prefix);
            }
        }
        assertTrue(orphans.isEmpty(),
                "AdminPaths 里的前缀没有任何 controller 挂载（拼写漂移？）: " + orphans);
    }
}
