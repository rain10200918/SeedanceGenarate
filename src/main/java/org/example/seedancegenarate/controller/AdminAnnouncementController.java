package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Announcement;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AnnouncementService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端公告：CRUD + 发布/下线（requireAdmin）。
 */
@RestController
@RequestMapping("/api/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    public Result<Page<Announcement>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status
    ) {
        requireAdmin();
        return Result.success(announcementService.page(current, size, status));
    }

    @PostMapping
    public Result<Announcement> create(@RequestBody AnnouncementForm form) {
        requireAdmin();
        if (form == null || !StringUtils.hasText(form.title()) || !StringUtils.hasText(form.content())) {
            throw new RuntimeException("标题和内容不能为空");
        }
        return Result.success(announcementService.create(form.title().trim(), form.content(), UserContext.getUserId()));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AnnouncementForm form) {
        requireAdmin();
        announcementService.update(id, form == null ? null : form.title(), form == null ? null : form.content());
        return Result.success(null);
    }

    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        requireAdmin();
        announcementService.publish(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/unpublish")
    public Result<Void> unpublish(@PathVariable Long id) {
        requireAdmin();
        announcementService.unpublish(id);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        requireAdmin();
        announcementService.remove(id);
        return Result.success(null);
    }

    /** 公告表单（title/content 均可选，按需更新） */
    public record AnnouncementForm(String title, String content) {
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
