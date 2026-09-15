package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyView;
import org.example.seedancegenarate.dto.ApiKeyQuotaView;
import org.example.seedancegenarate.dto.ApiKeyShareRequest;
import org.example.seedancegenarate.dto.CreateApiKeyResponse;
import org.example.seedancegenarate.dto.SelfApiKeyRequest;
import org.example.seedancegenarate.dto.UpdateApiKeyCallbackRequest;
import org.example.seedancegenarate.dto.RotateWebhookSecretResponse;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ApiKeyService;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.ConcurrencyLimit;
import org.example.seedancegenarate.service.ConcurrencyPolicy;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API Key 自助管理（用户自己的钥匙）。
 * <p>
 * <b>与 {@code ApiKeyAdminController} 严格分家，不复用它的任何方法。</b>
 * 管理端的 create 是从<b>请求体</b>读 {@code userId} 的（管理员可代任意用户签发）——
 * 复用它就等于把横向越权直接搬进用户接口。这里属主一律来自 {@link UserContext}。
 * <p>
 * 路径 {@code /api/api-keys} 落在 {@code authInterceptor} 的 {@code /api/**} 覆盖内
 * （必须登录），但<b>不在</b> {@code AdminPaths.PROTECTED_PREFIXES} 之下——它本来就不是管理接口。
 * <p>
 * 限流按<b>账号</b>分桶（见 {@code ApiKeyRateLimitInterceptor}），所以自助多建 key
 * 拿不到更多配额；本轮也不下放任何配额字段，用户没有可提的权。
 */
@Slf4j
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    /** 密钥业务服务：负责用户密钥的创建、查询、改名、撤销等核心操作 */
    private final ApiKeyService apiKeyService;

    /** 用户表 Mapper：用于查询用户实体的并发策略配置 */
    private final AppUserMapper appUserMapper;

    /** 并发策略器：根据用户角色计算该账号允许同时并发执行的最大生成任务数 */
    private final ConcurrencyPolicy concurrencyPolicy;

    /** 每账号 key 数量上限：挡住脚本无限建（每把都是一个泄漏面） */
    @Value("${api-key.max-per-user:50}")
    private int maxPerUser;

    /**
     * 【我的钥匙列表】
     * 查询当前登录用户创建的所有 API Key 列表
     * <p>
     * 接口路径：GET /api/api-keys
     * 注意：
     * 1. 库里只存储哈希值，因此返回结果中只有 keyPrefix（如 sk-abc...）用于界面辨识，绝无明文；
     * 2. 已软删除（撤销）的 Key 会被自动过滤，不出现在列表中。
     *
     * @return Result<List<ApiKeyView>>
     */
    @GetMapping
    public Result<List<ApiKeyView>> list() {
        Long userId = UserContext.requireUserId();
        return Result.success(apiKeyService.listByOwner(userId).stream()
                .map(key -> ApiKeyView.of(key, null))
                .toList());
    }

    /**
     * 【自助创建 API Key】
     * 用户在控制台自己创建一把用于调用开放 API 的密钥
     * <p>
     * 接口路径：POST /api/api-keys
     * 重要安全细节：
     * 1. 数量限制：每账号最多创建 maxPerUser 把 Key（默认 50），防止滥用；
     * 2. 明文只展示一次：plainKey 仅在本次 HTTP 返回，绝不落库，用户必须自行妥善保存；
     * 3. 自动生成 Webhook Secret：用于外部接收回调通知时的 HMAC-SHA256 验签。
     *
     * @param request 包含可选的 key 备注名称和 Webhook 回调地址
     * @param servletRequest 用于获取用户当前的客户端真实 IP
     * @return Result<CreateApiKeyResponse>
     */
    @PostMapping
    public Result<CreateApiKeyResponse> create(@RequestBody(required = false) SelfApiKeyRequest request,
                                               HttpServletRequest servletRequest) {
        Long userId = UserContext.requireUserId();
        // 1. 检查该用户现存 key 数量，超过阈值直接拦截
        long existing = apiKeyService.countByOwner(userId);
        if (existing >= maxPerUser) {
            throw BusinessException.badRequest(
                    "API Key 数量已达上限（" + maxPerUser + " 个，当前 " + existing + " 个），请先撤销不用的");
        }
        String name = request == null ? null : request.getName();
        String callbackUrl = request == null ? null : request.getCallbackUrl();
        // 2. 调用创建服务，传入属主 ID、默认名称、回调地址与 IP
        ApiKeyService.CreatedApiKey created = apiKeyService.createOwned(
                userId, defaultName(name, existing), callbackUrl,
                userId, IpUtils.getClientIp(servletRequest));
        log.info("用户自助创建 API Key: userId={}, keyId={}, prefix={}",
                userId, created.record().getId(), created.record().getKeyPrefix());
        // 3. 返回包含明文 Key 与 WebhookSecret 的响应
        return Result.success(new CreateApiKeyResponse(
                ApiKeyView.of(created.record(), null), created.plainKey(), created.record().getWebhookSecret()));
    }

    /**
     * 【更新 Webhook 回调地址】
     * 修改任务完成后的 HTTP 通知回调 URL
     * <p>
     * 接口路径：PATCH /api/api-keys/{id}/callback
     *
     * @param id 目标 API Key ID
     * @param request 包含新的 callbackUrl
     */
    @PatchMapping("/{id}/callback")
    public Result<Void> updateCallback(@PathVariable Long id,
                                      @RequestBody UpdateApiKeyCallbackRequest request) {
        Long owner = UserContext.requireUserId();
        if (request == null) throw BusinessException.badRequest("请提供回调配置");
        // updateCallbackOwned 内部会自动带上 owner 条件，若 key 不属于当前用户则返回 false
        if (!apiKeyService.updateCallbackOwned(id, owner, request.callbackUrl())) throw notFound();
        return Result.success(null);
    }

    /**
     * 【轮转 / 重置 Webhook 签名秘钥】
     * 当开发者怀疑自己的回调密钥泄露时，重新生成一个全新的 Webhook Secret
     * <p>
     * 接口路径：POST /api/api-keys/{id}/webhook-secret/rotate
     *
     * @param id 目标 API Key ID
     * @return Result<RotateWebhookSecretResponse> 包含新生成的 webhook secret
     */
    @PostMapping("/{id}/webhook-secret/rotate")
    public Result<RotateWebhookSecretResponse> rotateWebhookSecret(@PathVariable Long id) {
        return Result.success(new RotateWebhookSecretResponse(
                apiKeyService.rotateWebhookSecretOwned(id, UserContext.requireUserId())));
    }

    /**
     * 【修改密钥备注名称】
     * <p>
     * 接口路径：PATCH /api/api-keys/{id}
     *
     * @param id 目标 API Key ID
     * @param request 包含新的名称
     */
    @PatchMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody SelfApiKeyRequest request) {
        Long userId = UserContext.requireUserId();
        if (!apiKeyService.renameOwned(id, userId, request == null ? null : request.getName())) {
            throw notFound();
        }
        return Result.success(null);
    }

    /**
     * 【撤销 / 删除自己的 API Key】
     * 用户视角就是「删掉了」：立刻失效、从列表消失、不再占名额。
     * <p>
     * <b>架构设计亮点：库里的行保留（软删除语义）</b>
     * 数据库表 api_call_log 和 video_task 都以外键记录了 api_key_id。
     * 如果真把这行物理删除，历史消费账单将无法统计“这笔钱具体是哪把 Key 消耗的”。
     * 因此底层将状态标记为 REVOKED，同时具有幂等性（多次调用均返回成功）。
     * <p>
     * 接口路径：DELETE /api/api-keys/{id}
     */
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        Long userId = UserContext.requireUserId();
        if (!apiKeyService.revokeOwned(id, userId)) {
            throw notFound();
        }
        log.info("用户撤销 API Key: userId={}, keyId={}", userId, id);
        return Result.success(null);
    }

    /**
     * 【查询账号并发配额】
     * 查询我的主账号支持同时并发跑几个任务、已为各把 Key 分配了多少份额。
     * <p>
     * 接口路径：GET /api/api-keys/quota
     */
    @GetMapping("/quota")
    public Result<ApiKeyQuotaView> quota() {
        Long userId = UserContext.requireUserId();
        ConcurrencyLimit limit = concurrencyPolicy.resolve(appUserMapper.selectById(userId));
        return Result.success(new ApiKeyQuotaView(
                limit.accountMax(), apiKeyService.allocatedShare(userId), concurrencyPolicy.isShadow()));
    }

    /**
     * 【为某把 Key 分配并发任务数上限】
     * 给自己的某把 key 分配「同时可跑任务数」。
     * <p>
     * <b>安全设计考量（为什么允许用户自己配）：</b>
     * 这个字段允许用户自己设，是因为它<b>只能收紧、不能放大</b>：生效值恒为 min(账号总量, 本值)。
     * 超过账号总量时直接报错拒绝，不静默按总量保存，避免给用户造成错觉。
     * <p>
     * 接口路径：PATCH /api/api-keys/{id}/share
     */
    @PatchMapping("/{id}/share")
    public Result<Void> setShare(@PathVariable Long id, @RequestBody ApiKeyShareRequest request) {
        Long userId = UserContext.requireUserId();
        Integer share = request == null ? null : request.getMaxConcurrency();
        if (share != null && share < 0) {
            throw BusinessException.badRequest("同时可跑任务数不能为负；不限制请留空，0 表示停用这把密钥");
        }
        Integer accountMax = concurrencyPolicy.resolve(appUserMapper.selectById(userId)).accountMax();
        if (share != null && accountMax == null) {
            // 没有蛋糕就没法切。而且不拦的话，用户能给 50 把 key 各设一个份额，
            // 凭空往对账的扫描集合里塞 50 个桶 —— 自助给后台加负载
            throw BusinessException.badRequest(
                    "你的账号没有设置「同时可跑任务数」上限，不需要给单把密钥分配份额");
        }
        if (share != null && share > accountMax) {
            throw BusinessException.badRequest(
                    "单把密钥最多分配 " + accountMax + " 个（账号总量就这么多），你填的是 " + share);
        }
        if (!apiKeyService.setShareOwned(id, userId, share)) {
            throw notFound();
        }
        log.info("用户调整 key 份额: userId={}, keyId={}, share={}", userId, id, share);
        return Result.success(null);
    }

    /**
     * 安全辅助方法：不属于自己的 id 一律报「不存在（404）」，而不是「无权限（403）」。
     * 返回 403 会直接告诉攻击者该 ID 在全库中真实存在，相当于提供了一个可枚举的扫描探测漏洞。
     */
    private BusinessException notFound() {
        return BusinessException.notFound("API Key 不存在");
    }

    /** 备注名称默认值：用户未填写时自动命名为 "API Key N"，防止前端展示一片空白 */
    private String defaultName(String name, long existing) {
        return name == null || name.isBlank() ? "API Key " + (existing + 1) : name;
    }
}

