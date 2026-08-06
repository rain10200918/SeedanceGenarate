package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.InviteCode;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.InviteCodeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invite-codes")
@RequiredArgsConstructor
public class InviteCodeController {
    private final InviteCodeService inviteCodeService;

    @PostMapping
    public Result<InviteCode> generate() {
        requireAdmin();
        return Result.success(inviteCodeService.generate(UserContext.requireUserId()));
    }

    @GetMapping
    public Result<Page<InviteCode>> page(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "10") Long size
    ) {
        requireAdmin();
        return Result.success(inviteCodeService.pageCodes(current, size));
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
