package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyBudgetRequest;
import org.example.seedancegenarate.dto.ApiKeyBudgetView;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ApiKeyBudgetService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/api-keys/{id}/budget")
@RequiredArgsConstructor
public class ApiKeyBudgetAdminController {
    private final ApiKeyBudgetService budgets;

    @GetMapping
    public Result<ApiKeyBudgetView> get(@PathVariable Long id) {
        return Result.success(budgets.query(requireAdmin(), id));
    }

    @PatchMapping
    public Result<ApiKeyBudgetView> update(@PathVariable Long id, @RequestBody ApiKeyBudgetRequest request) {
        return Result.success(budgets.setLimit(requireAdmin(), id, request.limit(), request.expectedVersion()));
    }

    private long requireAdmin() {
        if (!UserContext.isAdmin()) throw BusinessException.forbidden("无权限访问");
        return UserContext.requireUserId();
    }
}
