package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ResetPasswordRequest;
import org.example.seedancegenarate.dto.UpdateUserRoleRequest;
import org.example.seedancegenarate.dto.UserProfileDetail;
import org.example.seedancegenarate.dto.UserSummary;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AdminStatsService;
import org.example.seedancegenarate.service.AppUserService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {
    private final AppUserService appUserService;
    private final AdminStatsService adminStatsService;

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
