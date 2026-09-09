package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiBalanceResponse;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiAccountControllerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void balanceUsesCurrentApiKeyOwnerAndReturnsExactAmounts() {
        // 【测什么】余额只按 UserContext 中的 Key 属主查询，并用 BigDecimal 精确返回可用、冻结、总额和币种
        // 【怎么算红】控制器改为使用传入的 userId、查询其他用户，或用 double 计算 total，这条必须失败
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("12.34"));
        wallet.setFrozen(new BigDecimal("1.66"));
        AtomicReference<Long> requestedOwner = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        WalletService walletService = walletService(wallet, requestedOwner, calls);

        AppUser owner = new AppUser();
        owner.setId(73L);
        UserContext.setUser(owner);

        ApiBalanceResponse response = new ApiAccountController(walletService).balance();

        assertEquals(new BigDecimal("12.34"), response.available());
        assertEquals(new BigDecimal("1.66"), response.frozen());
        assertEquals(new BigDecimal("14.00"), response.total());
        assertEquals("CNY", response.currency());
        assertEquals(73L, requestedOwner.get());
        assertEquals(1, calls.get());
    }

    @Test
    void balanceFailsClosedWithoutOwnerContext() {
        // 【测什么】没有 API Key 属主上下文时控制器失败关闭，不读取任何钱包
        // 【怎么算红】删掉 UserContext.requireUserId() 或改成默认用户 ID，这条会因未抛异常或钱包服务被调用而失败
        AtomicInteger calls = new AtomicInteger();
        WalletService walletService = walletService(null, new AtomicReference<>(), calls);
        ApiAccountController controller = new ApiAccountController(walletService);

        RuntimeException error = assertThrows(RuntimeException.class, controller::balance);

        assertEquals("请先登录", error.getMessage());
        assertEquals(0, calls.get());
    }

    private WalletService walletService(Wallet wallet, AtomicReference<Long> requestedOwner,
                                        AtomicInteger calls) {
        return (WalletService) Proxy.newProxyInstance(
                WalletService.class.getClassLoader(),
                new Class<?>[]{WalletService.class},
                (proxy, method, args) -> {
                    if ("getWallet".equals(method.getName())) {
                        calls.incrementAndGet();
                        requestedOwner.set((Long) args[0]);
                        return wallet;
                    }
                    throw new AssertionError("余额接口不应调用其他钱包方法: " + method.getName());
                });
    }
}
