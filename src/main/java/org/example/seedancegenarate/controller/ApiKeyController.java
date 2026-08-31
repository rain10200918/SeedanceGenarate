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

    private final ApiKeyService apiKeyService;
    private final AppUserMapper appUserMapper;
    private final ConcurrencyPolicy concurrencyPolicy;

    /** 每账号 key 数量上限：挡住脚本无限建（每把都是一个泄漏面） */
    @Value("${api-key.max-per-user:50}")
    private int maxPerUser;

    /**
     * 我的钥匙列表（只列在用的；明文不在其中，库里只有哈希，物理上返回不了）。
     * 已删除的不出现在这里，但行仍在库中——见 {@link #revoke} 的说明。
     */
    @GetMapping
    public Result<List<ApiKeyView>> list() {
        Long userId = UserContext.requireUserId();
        return Result.success(apiKeyService.listByOwner(userId).stream()
                .map(key -> ApiKeyView.of(key, null))
                .toList());
    }

    /** 自助创建：明文只在这个响应里出现一次 */
    @PostMapping
    public Result<CreateApiKeyResponse> create(@RequestBody(required = false) SelfApiKeyRequest request,
                                               HttpServletRequest servletRequest) {
        Long userId = UserContext.requireUserId();
        long existing = apiKeyService.countByOwner(userId);
        if (existing >= maxPerUser) {
            throw BusinessException.badRequest(
                    "API Key 数量已达上限（" + maxPerUser + " 个，当前 " + existing + " 个），请先撤销不用的");
        }
        String name = request == null ? null : request.getName();
        String callbackUrl = request == null ? null : request.getCallbackUrl();
        ApiKeyService.CreatedApiKey created = apiKeyService.createOwned(
                userId, defaultName(name, existing), callbackUrl,
                userId, IpUtils.getClientIp(servletRequest));
        log.info("用户自助创建 API Key: userId={}, keyId={}, prefix={}",
                userId, created.record().getId(), created.record().getKeyPrefix());
        return Result.success(new CreateApiKeyResponse(
                ApiKeyView.of(created.record(), null), created.plainKey()));
    }

    /** 改备注 */
    @PatchMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody SelfApiKeyRequest request) {
        Long userId = UserContext.requireUserId();
        if (!apiKeyService.renameOwned(id, userId, request == null ? null : request.getName())) {
            throw notFound();
        }
        return Result.success(null);
    }

    /**
     * 删除自己的钥匙。用户视角就是「删掉了」：立刻失效、从列表消失、不再占名额。
     * <p>
     * <b>库里的行保留</b>：{@code api_call_log.api_key_id} 与 {@code video_task.api_key_id}
     * 还指着它，真删行就再也答不出「这笔消费是哪把 key 花的」——企业按部门归因、
     * 账单争议时要的正是这个。幂等：重复删除照样成功。
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
     * 我的账号能同时跑几个、已经分出去多少 —— 用户要分配份额，得先看得到这两个数。
     */
    @GetMapping("/quota")
    public Result<ApiKeyQuotaView> quota() {
        Long userId = UserContext.requireUserId();
        ConcurrencyLimit limit = concurrencyPolicy.resolve(appUserMapper.selectById(userId));
        return Result.success(new ApiKeyQuotaView(
                limit.accountMax(), apiKeyService.allocatedShare(userId), concurrencyPolicy.isShadow()));
    }

    /**
     * 给自己的某把 key 分配「同时可跑任务数」。
     * <p>
     * <b>这个字段能放给用户自己设，唯一的理由是它只能收紧</b>（D-032）——
     * 生效值恒为 {@code min(账号总量, 本值)}，改它改不出更多容量。
     * <p>
     * 超过账号总量时<b>直接拒绝，不静默按总量保存</b>：静默截断的话页面显示 999、
     * 实际按 50 跑，用户会一直以为自己分配了 999 —— 和管理端那个「档位拼错静默失效」
     * 是同一类陷阱。
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
     * 不属于自己的 id 一律报「不存在」，而不是「无权限」。
     * 403 会告诉攻击者「这个 id 是存在的」，等于送一个可枚举的存在性预言机。
     */
    private BusinessException notFound() {
        return BusinessException.notFound("API Key 不存在");
    }

    /** 备注不强制填；不填给个能认出来的默认名，而不是留空让列表里一排「未命名」 */
    private String defaultName(String name, long existing) {
        return name == null || name.isBlank() ? "API Key " + (existing + 1) : name;
    }
}
