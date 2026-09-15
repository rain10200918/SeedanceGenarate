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
 * 【管理端控制器 - API Key 密钥管理】
 * <p>
 * 业务定位：
 * 专供平台【管理员（ADMIN）】使用，用于集中查看全平台所有用户持有的 API Key、
 * 替指定用户手动创建 API Key（明文只展示一次）、以及强制吊销/撤销违规或废弃的 Key。
 * <p>
 * 安全设计原则：
 * 1. 严格权限校验：每个方法入口都会调用 requireAdmin()，只有管理员角色才能访问；
 * 2. 与普通用户自助管理（ApiKeyController）分家：避免管理员代签接口被普通用户利用发生横向越权。
 * <p>
 * 访问前缀：/api/admin/api-keys
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    /** API Key 服务：负责密钥生成、加盐哈希存储、吊销与查询 */
    private final ApiKeyService apiKeyService;

    /** 用户服务：用于反查 Key 的属主用户名信息 */
    private final AppUserService appUserService;

    /** 接入文档服务：提供外部 API 接入的标准 Markdown 说明文档内容 */
    private final ApiDocService apiDocService;

    /**
     * 查询全平台所有 API Key 列表（管理员视图）
     * <p>
     * 接口路径：GET /api/admin/api-keys
     * 特性：返回结果脱敏，不含明文 Key，只展示前缀（keyPrefix）与属主用户名。
     *
     * @return Result<List<ApiKeyView>> 返回包含属主用户名和 Key 状态的列表
     */
    @GetMapping
    public Result<List<ApiKeyView>> list() {
        // 1. 安全检查：确认当前操作人是否是管理员
        requireAdmin();

        // 2. 从数据库查出所有的 ApiKey 记录
        List<ApiKey> keys = apiKeyService.listAll();

        // 3. 批量补全每个 Key 属主的用户名（避免 N+1 查询），组合后返回
        return Result.success(withUsernames(keys));
    }

    /**
     * 管理员代特定用户创建一把新的 API Key
     * <p>
     * 接口路径：POST /api/admin/api-keys
     * 注意：出于安全考虑，生成的密钥明文（plainKey，格式为 sk-xxxx）仅在本次 HTTP 响应中返回一次，
     * 数据库中只存储其 SHA-256 哈希值，后续任何人都无法再查到明文。
     *
     * @param request 包含指定的 userId、密钥名称、Webhook 回调地址
     * @return Result<CreateApiKeyResponse> 包含生成的明文 Key 与视图信息
     */
    @PostMapping
    public Result<CreateApiKeyResponse> create(@RequestBody CreateApiKeyRequest request) {
        // 1. 安全检查：确认管理员身份
        requireAdmin();

        // 2. 参数非空校验：管理员创建必须明确指定归属给哪个用户
        if (request == null || request.getUserId() == null) {
            throw new RuntimeException("userId 不能为空");
        }

        // 3. 调用业务层创建密钥（内部生成安全随机字符串并计算 SHA-256 存盘）
        ApiKeyService.CreatedApiKey created = apiKeyService.create(
                request.getUserId(), request.getName(), request.getCallbackUrl());

        // 4. 将明文 Key 与脱敏后的记录打包，仅此一次返回给管理员
        return Result.success(new CreateApiKeyResponse(
                ApiKeyView.of(created.record(), usernameOf(created.record().getUserId())), created.plainKey()));
    }

    /**
     * 内部辅助方法：批量补查并组装每把 Key 属主的用户名
     * 避免在循环里逐个调用 selectById 导致大量的数据库查询（N+1 查询问题）
     */
    private List<ApiKeyView> withUsernames(List<ApiKey> keys) {
        // 1. 提取所有不重复的 userId 列表
        List<Long> userIds = keys.stream().map(ApiKey::getUserId).distinct().toList();

        // 2. 一次性查询出这批用户的数据库记录，并转换为 Map<userId, username>
        Map<Long, String> usernames = userIds.isEmpty() ? Map.of()
                : appUserService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername, (a, b) -> a));

        // 3. 将 ApiKey 映射成前端视图 ApiKeyView，自动从 Map 中填入用户名
        return keys.stream()
                .map(key -> ApiKeyView.of(key, usernames.getOrDefault(key.getUserId(), null)))
                .toList();
    }

    /**
     * 内部辅助方法：根据单个用户 ID 查询其用户名
     */
    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        AppUser user = appUserService.getById(userId);
        return user == null ? null : user.getUsername();
    }

    /**
     * 撤销 / 吊销某把 API Key
     * <p>
     * 接口路径：POST /api/admin/api-keys/{id}/revoke
     * 说明：吊销后该 Key 立即失效，外部调用若继续使用此 Key 将直接被拦截器拒绝（401 Unauthorized）。
     *
     * @param id 要吊销的 ApiKey 数据库主键 ID
     * @return Result<Void>
     */
    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id) {
        requireAdmin();
        apiKeyService.revoke(id);
        return Result.success(null);
    }

    /**
     * 获取外部 API 接入文档内容（供管理端页面内嵌展示或预览）
     * <p>
     * 接口路径：GET /api/admin/api-keys/docs
     */
    @GetMapping("/docs")
    public Result<String> docs() {
        requireAdmin();
        return Result.success(apiDocService.content());
    }

    /**
     * 权限断言辅助方法：校验当前操作人是否具备管理员角色（ADMIN）
     * 若非管理员则直接抛出异常中断执行
     */
    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}

