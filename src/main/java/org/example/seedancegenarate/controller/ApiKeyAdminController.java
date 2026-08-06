package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyView;
import org.example.seedancegenarate.dto.CreateApiKeyRequest;
import org.example.seedancegenarate.dto.CreateApiKeyResponse;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.ApiDocService;
import org.example.seedancegenarate.service.ApiKeyService;
import org.example.seedancegenarate.service.AppUserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理员：对外 API 钥匙管理。创建（明文只展示一次）/ 撤销 / 列表（视图不含敏感字段，属主显示用户名）。
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {
    private final ApiKeyService apiKeyService;
    private final AppUserService appUserService;
    private final ApiDocService apiDocService;

    @GetMapping
    public Result<List<ApiKeyView>> list() {
        requireAdmin();
        List<ApiKey> keys = apiKeyService.listAll();
        return Result.success(withUsernames(keys));
    }

    @PostMapping
    public Result<CreateApiKeyResponse> create(@RequestBody CreateApiKeyRequest request) {
        requireAdmin();
        if (request == null || request.getUserId() == null) {
            throw new RuntimeException("userId 不能为空");
        }
        ApiKeyService.CreatedApiKey created = apiKeyService.create(
                request.getUserId(), request.getName(), request.getCallbackUrl());
        return Result.success(new CreateApiKeyResponse(
                ApiKeyView.of(created.record(), usernameOf(created.record().getUserId())), created.plainKey()));
    }

    /** 批量补查属主用户名 */
    private List<ApiKeyView> withUsernames(List<ApiKey> keys) {
        List<Long> userIds = keys.stream().map(ApiKey::getUserId).distinct().toList();
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of()
                : appUserService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername, (a, b) -> a));
        return keys.stream()
                .map(key -> ApiKeyView.of(key, usernames.getOrDefault(key.getUserId(), null)))
                .toList();
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        AppUser user = appUserService.getById(userId);
        return user == null ? null : user.getUsername();
    }

    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id) {
        requireAdmin();
        apiKeyService.revoke(id);
        return Result.success(null);
    }

    /** 接入文档（管理页渲染用；外部开发者走 GET /api/v1/docs，同一份资源） */
    @GetMapping("/docs")
    public Result<String> docs() {
        requireAdmin();
        return Result.success(apiDocService.content());
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
