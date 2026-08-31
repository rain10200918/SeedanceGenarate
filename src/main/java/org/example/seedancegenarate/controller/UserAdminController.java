package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ResetPasswordRequest;
import org.example.seedancegenarate.dto.ConcurrencyLimitRequest;
import org.example.seedancegenarate.dto.ConcurrencyLimitView;
import org.example.seedancegenarate.dto.UpdateUserRoleRequest;
import org.example.seedancegenarate.dto.UserProfileDetail;
import org.example.seedancegenarate.dto.UserSummary;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AdminStatsService;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.service.MeteredAccountRegistry;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {
    private final AppUserService appUserService;
    private final AdminStatsService adminStatsService;
    private final ConcurrencyPolicy concurrencyPolicy;
    private final MeteredAccountRegistry meteredAccountRegistry;

    /** 全局统计卡（全量聚合，与当前页无关） */
    @GetMapping("/summary")
    public Result<UserSummary> summary() {
        requireAdmin();
        return Result.success(adminStatsService.userSummary());
    }

    /** 单用户画像详情（任务/API 分布 + 消费 + 最近记录） */
    @GetMapping("/{userId}/detail")
    public Result<UserProfileDetail> detail(@PathVariable Long userId) {
        requireAdmin();
        return Result.success(adminStatsService.userDetail(userId));
    }

    /** 管理员重置用户密码 */
    @PutMapping("/{userId}/password")
    public Result<Void> resetPassword(@PathVariable Long userId, @RequestBody ResetPasswordRequest request) {
        requireAdmin();
        appUserService.resetPassword(userId, request.getPassword());
        return Result.<Void>success(null);
    }

    @GetMapping
    public Result<Page<AppUser>> page(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "10") Long size,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        requireAdmin();
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        Page<AppUser> page = appUserService.page(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<AppUser>lambdaQuery()
                        .like(StringUtils.hasText(keyword), AppUser::getUsername, keyword)
                        .orderByDesc(AppUser::getCreateTime)
                        .orderByDesc(AppUser::getId)
        );
        page.getRecords().forEach(user -> user.setPassword(null));
        return Result.success(page);
    }

    @PutMapping("/{userId}/role")
    public Result<AppUser> updateRole(@PathVariable Long userId, @RequestBody UpdateUserRoleRequest request) {
        requireAdmin();
        String role = normalizeRole(request.getRole());
        AppUser user = appUserService.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        AppUser update = new AppUser();
        update.setId(userId);
        update.setRole(role);
        appUserService.updateById(update);
        AppUser updated = appUserService.getById(userId);
        updated.setPassword(null);
        return Result.success(updated);
    }

    /** 查某账号的在途并发额度（含生效值、来源、以及会静默失效时的告警） */
    @GetMapping("/{userId}/concurrency")
    public Result<ConcurrencyLimitView> concurrency(@PathVariable Long userId) {
        requireAdmin();
        return Result.success(concurrencyPolicy.describe(requireUser(userId)));
    }

    /**
     * 设置在途并发额度。
     * <p>
     * 返回的是<b>后端算出来的生效值</b>，不是请求里填了什么 —— 档位名拼错时
     * {@code resolve} 会静默按「不限」放行，页面必须让管理员当场看见这件事。
     * <p>
     * 这是一次<b>商业行为</b>（改的是客户买到的东西），所以必须留痕：
     * 谁改的、改前是什么、改后是什么。系统里目前没有专门的审计表，先落日志。
     */
    @PutMapping("/{userId}/concurrency")
    public Result<ConcurrencyLimitView> updateConcurrency(@PathVariable Long userId,
                                                          @RequestBody ConcurrencyLimitRequest request) {
        requireAdmin();
        AppUser before = requireUser(userId);
        Integer override = request.getConcurrencyOverride();
        if (override != null && override < 0) {
            throw new RuntimeException("并发上限不能为负数；要「不限」请清空，填 0 表示禁止提交");
        }
        String tier = StringUtils.hasText(request.getAccountTier()) ? request.getAccountTier().trim() : null;

        // 两个字段都用 UpdateWrapper 显式 set：MyBatis-Plus 的 updateById 会跳过 null 字段，
        // 那样「清空」这个动作就永远执行不了，管理员会以为清了其实没清。
        appUserService.update(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, userId)
                .set(AppUser::getAccountTier, tier)
                .set(AppUser::getConcurrencyOverride, override));

        // 席位数决定这个账号走哪档令牌桶。不失效的话，改完之后接口限速还要等最多 30 秒
        // 才跟上 —— 管理员点完保存立刻去测，会看到「设了 50 席却还在按 25 席拦」
        meteredAccountRegistry.invalidate();

        ConcurrencyLimitView view = concurrencyPolicy.describe(requireUser(userId));
        log.warn("管理员调整并发额度: operator={}, userId={}, 档位 {} → {}, 覆盖值 {} → {}, 生效={}",
                UserContext.getUserId(), userId,
                before.getAccountTier(), tier,
                before.getConcurrencyOverride(), override,
                view.effectiveLimit() == null ? "不限" : view.effectiveLimit());
        return Result.success(view);
    }

    private AppUser requireUser(Long userId) {
        AppUser user = appUserService.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            throw new RuntimeException("角色不能为空");
        }
        String normalized = role.trim().toUpperCase();
        if (!"ADMIN".equals(normalized) && !"USER".equals(normalized)) {
            throw new RuntimeException("角色只能是 ADMIN 或 USER");
        }
        return normalized;
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
