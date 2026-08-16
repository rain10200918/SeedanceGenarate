package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.Announcement;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AnnouncementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端公告：已发布列表（进程快照，最新在前）。
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    public Result<List<Announcement>> published() {
        return Result.success(announcementService.published());
    }
}
