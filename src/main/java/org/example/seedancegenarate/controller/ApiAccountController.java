package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiBalanceResponse;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 【对外 API - 账户与财务信息控制器】
 * <p>
 * 业务定位：
 * 面向持 API Key（sk- 开头）调用的外部开发者，提供查询当前账号余额和当前 Key 预算消费情况的接口。
 * <p>
 * 访问前缀：/api/v1/account
 * 安全说明：受 ApiKeyInterceptor 拦截保护，请求头必须携带有效的 Authorization: Bearer sk-...
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class ApiAccountController {

    /** 钱包服务：负责读取与操作用户钱包余额、冻结金额等 */
    private final WalletService walletService;

    /** API Key 预算服务：负责查询与管理单把 API Key 的每月消费预算限额及已消耗金额 */
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.seedancegenarate.service.ApiKeyBudgetService budgets;

    /**
     * 查询当前调用的这把 API Key 的预算消耗情况
     * <p>
     * 接口路径：GET /api/v1/account/key-budget
     *
     * @param request HTTP 请求对象，拦截器在鉴权通过后会将解析出的 ApiKey 实体存放在 request 属性 "api_key" 中
     * @return ApiKeyBudgetView 返回该 Key 的预算上限、本月已用额度、剩余额度等视图
     */
    @GetMapping("/key-budget")
    public org.example.seedancegenarate.dto.ApiKeyBudgetView keyBudget(jakarta.servlet.http.HttpServletRequest request) {
        // 1. 安全防线：从 HttpServletRequest 中取出拦截器设置的 ApiKey 对象
        // 2. 校验此 ApiKey 必须存在，且所属用户 ID 必须与上下文当前用户 ID 一致，防止越权盗用
        if (!(request.getAttribute("api_key") instanceof org.example.seedancegenarate.entity.ApiKey key)
                || !java.util.Objects.equals(key.getUserId(), UserContext.requireUserId())) {
            throw org.example.seedancegenarate.exception.ApiException.invalidApiKey();
        }
        // 3. 调用预算服务，查询属于该用户下的这把 Key 的实时预算与消耗
        return budgets.query(key.getUserId(), key.getId());
    }

    /**
     * 查询主账号的钱包余额信息
     * <p>
     * 接口路径：GET /api/v1/account/balance
     * 说明：外部开发者调用此接口了解自己当前账号还有多少可用余额，避免生成任务因余额不足失败。
     *
     * @return ApiBalanceResponse 包含：可用余额 (available)、冻结金额 (frozen)、总余额 (total)、币种 (CNY)
     */
    @GetMapping("/balance")
    public ApiBalanceResponse balance() {
        // 1. 从当前线程上下文中获取发起请求的属主用户 ID
        Long ownerId = UserContext.requireUserId();

        // 2. 从数据库中获取该用户的钱包实体
        Wallet wallet = walletService.getWallet(ownerId);

        // 3. 取出可用余额（随时可以用来生成视频/图片的钱）
        BigDecimal available = wallet.getBalance();

        // 4. 取出正在处理中的任务所预先冻结的算力费用
        BigDecimal frozen = wallet.getFrozen();

        // 5. 组合成标准对外响应对象并返回，总额 = 可用 + 冻结，币种默认为 CNY（人民币）
        return new ApiBalanceResponse(available, frozen, available.add(frozen), "CNY");
    }
}

