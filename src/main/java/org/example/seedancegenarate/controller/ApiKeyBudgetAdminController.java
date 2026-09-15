package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyBudgetRequest;
import org.example.seedancegenarate.dto.ApiKeyBudgetView;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ApiKeyBudgetService;
import org.springframework.web.bind.annotation.*;

/**
 * 【管理端控制器 - API Key 预算配额管理】
 * <p>
 * 业务定位：
 * 专供【管理员（ADMIN）】使用，对指定的一把 API Key 进行预算上限查询与修改。
 * 管理员有最高权限，可以把某把 Key 的月度消费限额调高或调低，即使这把 Key 不属于管理员自己。
 * <p>
 * 访问前缀：/api/admin/api-keys/{id}/budget
 */
@RestController
@RequestMapping("/api/admin/api-keys/{id}/budget")
@RequiredArgsConstructor
public class ApiKeyBudgetAdminController {

    /** 预算服务：负责单 Key 预算限额的 CAS 版本控制、更新与核算 */
    private final ApiKeyBudgetService budgets;

    /**
     * 管理员查询指定 API Key 的预算详情
     * <p>
     * 接口路径：GET /api/admin/api-keys/{id}/budget
     *
     * @param id 目标 API Key 的数据库主键 ID
     * @return Result<ApiKeyBudgetView> 返回该 Key 的预算上限、当前版本号、已用金额等
     */
    @GetMapping
    public Result<ApiKeyBudgetView> get(@PathVariable Long id) {
        // 校验管理员权限，并返回管理员查询到的预算视图
        return Result.success(budgets.query(requireAdmin(), id));
    }

    /**
     * 管理员更新指定 API Key 的预算上限
     * <p>
     * 接口路径：PATCH /api/admin/api-keys/{id}/budget
     *
     * @param id 目标 API Key 的数据库主键 ID
     * @param request 包含新的预算限额 limit 以及预期的并发控制版本号 expectedVersion（防并发覆盖）
     * @return Result<ApiKeyBudgetView> 更新生效后的最新预算视图
     */
    @PatchMapping
    public Result<ApiKeyBudgetView> update(@PathVariable Long id, @RequestBody ApiKeyBudgetRequest request) {
        return Result.success(budgets.setLimit(requireAdmin(), id, request.limit(), request.expectedVersion()));
    }

    /**
     * 权限辅助方法：断言当前登录用户必须是管理员
     *
     * @return 当前管理员用户的 userId
     * @throws BusinessException 403 Forbidden（无权限访问）
     */
    private long requireAdmin() {
        if (!UserContext.isAdmin()) throw BusinessException.forbidden("无权限访问");
        return UserContext.requireUserId();
    }
}

