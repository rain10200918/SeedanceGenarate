package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyUserView;
import org.example.seedancegenarate.dto.ChangePasswordRequest;
import org.example.seedancegenarate.dto.UserStatsResponse;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.UserProfileService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 个人中心（登录用户）：统计 / 我的 API Key / 修改密码。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserProfileController {
    private final UserProfileService userProfileService;

    @GetMapping("/stats")
    public Result<UserStatsResponse> stats() {
        return Result.success(userProfileService.stats(UserContext.requireUserId()));
    }

    @GetMapping("/api-keys")
    public Result<List<ApiKeyUserView>> apiKeys() {
        return Result.success(userProfileService.apiKeys(UserContext.requireUserId()));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        if (request == null) {
            throw new RuntimeException("请求体不能为空");
        }
        userProfileService.changePassword(UserContext.requireUserId(),
                request.getOldPassword(), request.getNewPassword());
        return Result.success(null);
    }
}
