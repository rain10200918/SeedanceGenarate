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

@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class ApiAccountController {

    private final WalletService walletService;

    @GetMapping("/balance")
    public ApiBalanceResponse balance() {
        Long ownerId = UserContext.requireUserId();
        Wallet wallet = walletService.getWallet(ownerId);
        BigDecimal available = wallet.getBalance();
        BigDecimal frozen = wallet.getFrozen();
        return new ApiBalanceResponse(available, frozen, available.add(frozen), "CNY");
    }
}
