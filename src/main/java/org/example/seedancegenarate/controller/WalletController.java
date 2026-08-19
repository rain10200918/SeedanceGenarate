package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.WalletOverview;
import org.example.seedancegenarate.dto.WalletSpendingSummary;
import org.example.seedancegenarate.dto.WalletSpendingView;
import org.example.seedancegenarate.entity.BalanceTransaction;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧钱包：概览、余额流水、消费统计与消费明细。
 * 所有接口从 UserContext 取得 userId，不信任前端传入的用户 ID。
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** 钱包概览（余额 / 冻结 / 总额） */
    @GetMapping("/me")
    public Result<WalletOverview> me() {
        Wallet wallet = walletService.getWallet(UserContext.requireUserId());
        return Result.success(new WalletOverview(
                wallet.getBalance(),
                wallet.getFrozen(),
                wallet.getBalance().add(wallet.getFrozen()),
                "CNY"));
    }

    /** 我的余额流水分页；type 可选，userId 永远来自登录上下文 */
    @GetMapping("/transactions")
    public Result<Page<BalanceTransaction>> transactions(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "20") Long size,
            @RequestParam(value = "type", required = false) String type
    ) {
        return Result.success(walletService.pageTransactions(
                UserContext.requireUserId(), current, size,
                StringUtils.hasText(type) ? type : null));
    }

    /** 我的消费概览：金额只来自 SETTLE 流水 */
    @GetMapping("/spending-summary")
    public Result<WalletSpendingSummary> spendingSummary() {
        return Result.success(walletService.spendingSummary(UserContext.requireUserId()));
    }

    /** 我的消费明细分页：SETTLE 流水关联 video_task */
    @GetMapping("/spending")
    public Result<Page<WalletSpendingView>> spending(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "20") Long size
    ) {
        return Result.success(walletService.pageSpending(UserContext.requireUserId(), current, size));
    }
}
