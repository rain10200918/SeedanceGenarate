package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyBudgetRequest;
import org.example.seedancegenarate.dto.ApiKeyBudgetView;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ApiKeyBudgetService;
import org.example.seedancegenarate.service.ApiKeyService;
import org.springframework.web.bind.annotation.*;

/** Session owner endpoints. Even an admin must use the admin route for another owner's key. */
@RestController
@RequestMapping("/api/api-keys/{id}/budget")
@RequiredArgsConstructor
public class ApiKeyBudgetController {
    private final ApiKeyBudgetService budgets;
    private final ApiKeyService keys;

    @GetMapping
    public Result<ApiKeyBudgetView> get(@PathVariable Long id) {
        long owner = requireOwner(id);
        return Result.success(budgets.query(owner, id));
    }

    @PatchMapping
    public Result<ApiKeyBudgetView> update(@PathVariable Long id, @RequestBody ApiKeyBudgetRequest request) {
        long owner = requireOwner(id);
        return Result.success(budgets.setLimit(owner, id, request.limit(), request.expectedVersion()));
    }

    private long requireOwner(Long id) {
        long owner = UserContext.requireUserId();
        // SQL ownership is already implemented by this existing service; do not reveal foreign keys.
        if (id == null || keys.listByOwner(owner).stream().noneMatch(k -> id.equals(k.getId()))) {
            throw BusinessException.notFound("API Key 不存在");
        }
        return owner;
    }
}
