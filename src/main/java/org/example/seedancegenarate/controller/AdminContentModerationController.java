package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ContentModerationRequest;
import org.example.seedancegenarate.entity.ContentModerationAction;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ContentModerationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 管理员生成内容审核：查看、屏蔽、恢复与不可覆盖的操作历史。 */
@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
public class AdminContentModerationController {

    private final ContentModerationService moderationService;

    @GetMapping("/tasks")
    public Result<Page<VideoTask>> tasks(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "20") long size,
                                         @RequestParam(defaultValue = "ALL") String status,
                                         @RequestParam(required = false) String keyword) {
        requireAdmin();
        return Result.success(moderationService.page(current, size, status, keyword));
    }

    @PostMapping("/tasks/{id}/block")
    public Result<VideoTask> block(@PathVariable Long id, @RequestBody ContentModerationRequest request) {
        requireAdmin();
        return Result.success(moderationService.block(id, request, UserContext.requireUserId()));
    }

    @PostMapping("/tasks/{id}/restore")
    public Result<VideoTask> restore(@PathVariable Long id, @RequestBody ContentModerationRequest request) {
        requireAdmin();
        return Result.success(moderationService.restore(id, request, UserContext.requireUserId()));
    }

    @GetMapping("/tasks/{id}/history")
    public Result<List<ContentModerationAction>> history(@PathVariable Long id) {
        requireAdmin();
        return Result.success(moderationService.history(id));
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) throw BusinessException.forbidden("需要管理员权限");
    }
}
