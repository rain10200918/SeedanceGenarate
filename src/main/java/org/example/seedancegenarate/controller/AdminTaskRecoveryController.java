package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AdminTaskRecoveryView;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AdminTaskRecoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员处理“供应商可能接单但响应丢失”的任务；不暴露盲目重投。 */
@RestController
@RequestMapping("/api/admin/task-recovery")
@RequiredArgsConstructor
public class AdminTaskRecoveryController {
    private final AdminTaskRecoveryService recoveryService;

    @GetMapping("/{taskId}")
    public Result<AdminTaskRecoveryView> detail(@PathVariable String taskId) {
        requireAdmin();
        return Result.success(recoveryService.find(taskId));
    }

    /** 「再查一次」：现在就去节点上找，找到直接接回。返回的是整句人话，前端原样展示 */
    @PostMapping("/{taskId}/lookup")
    public Result<AdminTaskRecoveryService.LookupResult> lookup(@PathVariable String taskId) {
        requireAdmin();
        return Result.success(recoveryService.lookup(taskId));
    }

    @PostMapping("/{taskId}/bind")
    public Result<Void> bind(@PathVariable String taskId, @RequestBody BindRequest request) {
        requireAdmin();
        if (request == null) {
            throw BusinessException.badRequest("请求参数不能为空");
        }
        recoveryService.bind(taskId, request.promptId(), request.nodeId());
        return Result.success(null);
    }

    @PostMapping("/{taskId}/terminate")
    public Result<Void> terminate(@PathVariable String taskId, @RequestBody(required = false) TerminateRequest request) {
        requireAdmin();
        recoveryService.terminate(taskId, request == null ? null : request.reason());
        return Result.success(null);
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw BusinessException.forbidden("无权限访问");
        }
    }

    public record BindRequest(String promptId, String nodeId) {
    }

    public record TerminateRequest(String reason) {
    }
}
