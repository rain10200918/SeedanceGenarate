package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AdminRechargeRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.BalanceTransaction;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.mapper.BalanceTransactionMapper;
import org.example.seedancegenarate.mapper.WalletMapper;
import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;
import org.example.seedancegenarate.service.RechargeService;
import org.example.seedancegenarate.service.WalletService;
import org.example.seedancegenarate.service.AppUserService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 管理端钱包：管理员加钱（admin 渠道，带操作人/原因留痕）、钱包查询、流水分页。
 */
@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
public class AdminWalletController {

    private final RechargeService rechargeService;
    private final WalletService walletService;
    private final WalletMapper walletMapper;
    private final BalanceTransactionMapper balanceTransactionMapper;
    private final AppUserService appUserService;

    /** 管理员加钱（来源 = admin 渠道，操作人 = 当前管理员，原因必填） */
    @PostMapping("/recharge")
    public Result<Void> recharge(@RequestBody AdminRechargeRequest request) {
        requireAdmin();
        AppUser operator = UserContext.getUser();
        AppUser target = appUserService.getById(request.getUserId());
        if (target == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new RuntimeException("请填写加钱原因");
        }
        rechargeService.recharge(RechargeOrder.CHANNEL_ADMIN, new RechargeCommand(
                request.getUserId(), request.getAmount(),
                operator.getId(), operator.getUsername(), request.getReason(), request.getRequestId()));
        return Result.<Void>success(null);
    }

    /** 用户钱包（余额 + 冻结） */
    @GetMapping("/{userId}")
    public Result<Wallet> wallet(@PathVariable Long userId) {
        requireAdmin();
        return Result.success(walletService.getWallet(userId));
    }

    /** 用户余额流水分页（type/金额±/余额/来源/操作人/时间） */
    @GetMapping("/{userId}/transactions")
    public Result<Page<BalanceTransaction>> transactions(
            @PathVariable Long userId,
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "10") Long size
    ) {
        requireAdmin();
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        Page<BalanceTransaction> page = balanceTransactionMapper.selectPage(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<BalanceTransaction>lambdaQuery()
                        .eq(BalanceTransaction::getUserId, userId)
                        .orderByDesc(BalanceTransaction::getCreateTime)
                        .orderByDesc(BalanceTransaction::getId)
        );
        return Result.success(page);
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }
}
