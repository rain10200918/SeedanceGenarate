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

/**
 * 【用户端控制器 - API Key 预算自助管理】
 * <p>
 * 业务定位：
 * 面向【普通登录用户（所有者 Owner）】，用于管理属于自己名下的某把 API Key 的预算。
 * <p>
 * 安全设计原则：
 * 严格属主隔离（Owner Isolation）：
 * 用户只能查询和修改属于自己的 Key，如果访问了别人名下的 Key ID，系统一律返回 404（API Key 不存在），
 * 绝不返回 403，防止攻击者通过遍历 ID 探测系统中存在哪些别人的 Key（防止可枚举存在性预言机攻击）。
 * 即使是管理员，如果要在用户路由操作别人的 Key 也不被允许，必须走专门的管理员路由。
 * <p>
 * 访问前缀：/api/api-keys/{id}/budget
 */
@RestController
@RequestMapping("/api/api-keys/{id}/budget")
@RequiredArgsConstructor
public class ApiKeyBudgetController {

    /** 预算服务：处理预算限额的校验与版本更新 */
    private final ApiKeyBudgetService budgets;

    /** 密钥服务：用于查询属于当前用户的密钥列表以进行属主校验 */
    private final ApiKeyService keys;

    /**
     * 用户查询属于自己的某把 API Key 的预算详情
     * <p>
     * 接口路径：GET /api/api-keys/{id}/budget
     *
     * @param id 目标 API Key 的数据库主键 ID
     * @return Result<ApiKeyBudgetView> 包含预算总额、已用额度、剩余额度与并发版本号
     */
    @GetMapping
    public Result<ApiKeyBudgetView> get(@PathVariable Long id) {
        // 1. 严格校验这把 key 必须属于当前登录用户
        long owner = requireOwner(id);
        // 2. 查询并返回预算详情
        return Result.success(budgets.query(owner, id));
    }

    /**
     * 用户调整属于自己的某把 API Key 的预算限额
     * <p>
     * 接口路径：PATCH /api/api-keys/{id}/budget
     *
     * @param id 目标 API Key 的数据库主键 ID
     * @param request 包含用户期望设定的限额 limit 及预期的版本号 expectedVersion（CAS 乐观锁）
     * @return Result<ApiKeyBudgetView> 最新的预算状态
     */
    @PatchMapping
    public Result<ApiKeyBudgetView> update(@PathVariable Long id, @RequestBody ApiKeyBudgetRequest request) {
        // 1. 严格校验属主身份
        long owner = requireOwner(id);
        // 2. 调用预算服务保存新限额，带有乐观锁版本校验防止多窗口并发覆盖
        return Result.success(budgets.setLimit(owner, id, request.limit(), request.expectedVersion()));
    }

    /**
     * 属主检查辅助方法：
     * 确认目标 Key 必须存在，且必须属于当前正在登录调用的用户
     *
     * @param id 传入的 API Key ID
     * @return 当前登录用户的 userId
     * @throws BusinessException 找不到或不属于自己时抛出 404 Not Found
     */
    private long requireOwner(Long id) {
        // 从当前登录会话提取当前操作人 ID
        long owner = UserContext.requireUserId();

        // 查出该用户所有在用的 key，验证目标 id 是否包含在其中
        // 注意：不属于自己的一律报“不存在”，绝不报“无权限”，防止黑客推测 ID 的存在性
        if (id == null || keys.listByOwner(owner).stream().noneMatch(k -> id.equals(k.getId()))) {
            throw BusinessException.notFound("API Key 不存在");
        }
        return owner;
    }
}

