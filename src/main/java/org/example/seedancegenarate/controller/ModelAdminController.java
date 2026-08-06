package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ModelAccessView;
import org.example.seedancegenarate.dto.UpdateModelAccessRequest;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.ModelAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员：模型开放管理。列出所有模型及开关态、切换某模型开 / 关。
 * 仅管理员可访问（沿用 {@link UserContext#isAdmin()}）。
 */
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class ModelAdminController {
    private final ModelAccessService modelAccessService;

    @GetMapping
    public Result<List<ModelAccessView>> list() {
        requireAdmin();
        return Result.success(modelAccessService.listAll());
    }

    @PutMapping("/{model}")
    public Result<Void> updateAccess(@PathVariable String model, @RequestBody UpdateModelAccessRequest request) {
        requireAdmin();
        if (request == null || request.getOpen() == null) {
            throw new RuntimeException("open 不能为空");
        }
        modelAccessService.setOpen(model, request.getOpen());
        return Result.success(null);
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
