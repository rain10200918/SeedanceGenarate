package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.entity.BalanceTransaction;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.mapper.BalanceTransactionMapper;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.mapper.WalletMapper;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 钱包账务纯单测（Mockito mock mapper）：幂等（biz_key 撞键跳过）、余额不足拒绝、
 * 冻结/结算/解冻正确性。真实唯一约束由 DB 保证（V12 uk_bt_biz_key），这里验证代码路径与无死锁路径。
 */
class WalletServiceTest {

    private final WalletMapper walletMapper = mock(WalletMapper.class);
    private final BalanceTransactionMapper btMapper = mock(BalanceTransactionMapper.class);
    private final RechargeOrderMapper rechargeOrderMapper = mock(RechargeOrderMapper.class);
    private final WalletServiceImpl service = new WalletServiceImpl(walletMapper, btMapper, rechargeOrderMapper);

    @BeforeEach
    void setUp() {
        // 模拟 MyBatis-Plus insert 自动填充自增 ID
        when(btMapper.insert(any(BalanceTransaction.class))).thenAnswer(inv -> {
            BalanceTransaction bt = inv.getArgument(0);
            bt.setId(1001L);
            return 1;
        });
        when(btMapper.updateBalanceAfter(any(), any(), any())).thenReturn(1);
    }

    private static WalletService.CreditContext adminCtx(String bizKey) {
        return WalletService.CreditContext.adminCredit(bizKey, bizKey, 9L, "admin", "测试加钱");
    }

    @Test
    void creditInsertsLedgerThenUpdatesBalance() {
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("10.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        when(walletMapper.addBalance(1L, new BigDecimal("10.00"))).thenReturn(1);

        service.credit(1L, new BigDecimal("10.00"), adminCtx("RC1"));

        verify(walletMapper).addBalance(1L, new BigDecimal("10.00"));
        // 存在钱包时不产生无谓的 insertIgnore 锁竞争
        verify(walletMapper, never()).insertIgnore(any());
        // 回填 balance_after
        verify(btMapper).updateBalanceAfter(eq(1001L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void creditLazyCreatesWalletWhenMissing() {
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("10.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        // 第一次 addBalance 找不到行返回 0，懒建后第二次返回 1
        when(walletMapper.addBalance(1L, new BigDecimal("10.00"))).thenReturn(0, 1);

        service.credit(1L, new BigDecimal("10.00"), adminCtx("RC1"));

        verify(walletMapper).insertIgnore(1L);
        verify(walletMapper, times(2)).addBalance(1L, new BigDecimal("10.00"));
        verify(btMapper).updateBalanceAfter(eq(1001L), eq(new BigDecimal("10.00")), any());
    }

    @Test
    void creditDuplicateBizKeySkipsBalanceUpdate() {
        // 撞唯一键（重复回调/重放）→ 已入账，绝不双倍
        doThrow(new DuplicateKeyException("dup")).when(btMapper).insert(any(BalanceTransaction.class));

        service.credit(1L, new BigDecimal("10.00"), adminCtx("RC1"));

        verify(walletMapper, never()).addBalance(any(), any());
    }

    @Test
    void creditRejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.credit(1L, new BigDecimal("0"), adminCtx("RC0")));
        assertThrows(IllegalArgumentException.class,
                () -> service.credit(1L, new BigDecimal("-1"), adminCtx("RC-1")));
    }

    @Test
    void freezeMovesBalanceToFrozen() {
        when(walletMapper.freeze(eq(1L), eq(new BigDecimal("5.00")))).thenReturn(1);
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("5.00"));
        wallet.setFrozen(new BigDecimal("5.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        assertTrue(service.freeze(1L, new BigDecimal("5.00"), 100L));

        var captor = org.mockito.ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(btMapper).insert(captor.capture());
        BalanceTransaction bt = captor.getValue();
        assertEquals(BalanceTransaction.TYPE_FREEZE, bt.getType());
        assertEquals(0, BigDecimal.ZERO.compareTo(bt.getAmount()));
        assertEquals(0, new BigDecimal("5.00").compareTo(bt.getHoldAmount()));
        assertEquals("task:100", bt.getBizKey());
        assertEquals(100L, bt.getTaskId());
        verify(walletMapper).freeze(1L, new BigDecimal("5.00"));
        verify(btMapper).updateBalanceAfter(eq(1001L), eq(new BigDecimal("5.00")), eq(new BigDecimal("5.00")));
    }

    @Test
    void freezeInsufficientBalanceThrows() {
        // CAS 条件更新 0 行，且钱包已存在 = 真实余额不足 → 异常（事务回滚流水与余额变更）
        when(walletMapper.freeze(eq(1L), any())).thenReturn(0);
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("1.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        assertThrows(WalletServiceImpl.InsufficientBalanceException.class,
                () -> service.freeze(1L, new BigDecimal("5.00"), 100L));
    }

    @Test
    void freezeZeroAmountSkips() {
        assertTrue(service.freeze(1L, new BigDecimal("0.00"), 100L));
        verify(btMapper, never()).insert(any(BalanceTransaction.class));
    }

    @Test
    void freezeDuplicateTaskSkipsWithoutRefreezing() {
        // 同一任务重复提交冻结 → 撞 biz_key 幂等，不重复扣
        doThrow(new DuplicateKeyException("dup")).when(btMapper).insert(any(BalanceTransaction.class));

        assertTrue(service.freeze(1L, new BigDecimal("5.00"), 100L));
        verify(walletMapper, never()).freeze(any(), any());
    }

    @Test
    void settleMovesFrozenToConsumed() {
        when(walletMapper.settle(eq(1L), eq(new BigDecimal("5.00")))).thenReturn(1);
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("0.00"));
        wallet.setFrozen(new BigDecimal("0.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        service.settle(1L, new BigDecimal("5.00"), 100L);

        var captor = org.mockito.ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(btMapper).insert(captor.capture());
        assertEquals(BalanceTransaction.TYPE_SETTLE, captor.getValue().getType());
        assertEquals(0, new BigDecimal("5.00").compareTo(captor.getValue().getHoldAmount()));
        assertEquals("task:100:settle", captor.getValue().getBizKey());
        verify(walletMapper).settle(1L, new BigDecimal("5.00"));
        verify(btMapper).updateBalanceAfter(eq(1001L), eq(new BigDecimal("0.00")), eq(new BigDecimal("0.00")));
    }

    @Test
    void settleDuplicateIsIdempotent() {
        doThrow(new DuplicateKeyException("dup")).when(btMapper).insert(any(BalanceTransaction.class));

        service.settle(1L, new BigDecimal("5.00"), 100L);
        verify(walletMapper, never()).settle(any(), any());
    }

    @Test
    void releaseReturnsFrozenToBalance() {
        when(walletMapper.release(eq(1L), eq(new BigDecimal("5.00")))).thenReturn(1);
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("5.00"));
        wallet.setFrozen(new BigDecimal("0.00"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        service.release(1L, new BigDecimal("5.00"), 100L);

        var captor = org.mockito.ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(btMapper).insert(captor.capture());
        BalanceTransaction bt = captor.getValue();
        assertEquals(BalanceTransaction.TYPE_RELEASE, bt.getType());
        assertEquals(0, BigDecimal.ZERO.compareTo(bt.getAmount()));
        assertEquals(0, new BigDecimal("5.00").compareTo(bt.getHoldAmount()));
        assertEquals("task:100:release", bt.getBizKey());
        verify(walletMapper).release(1L, new BigDecimal("5.00"));
        verify(btMapper).updateBalanceAfter(eq(1001L), eq(new BigDecimal("5.00")), eq(new BigDecimal("0.00")));
    }

    @Test
    void releaseDuplicateIsIdempotent() {
        doThrow(new DuplicateKeyException("dup")).when(btMapper).insert(any(BalanceTransaction.class));

        service.release(1L, new BigDecimal("5.00"), 100L);
        verify(walletMapper, never()).release(any(), any());
    }

    @Test
    void getWalletCreatesIfMissing() {
        when(walletMapper.selectOne(any())).thenReturn(null, new Wallet());
        service.getWallet(1L);
        verify(walletMapper).insertIgnore(1L);
        verify(walletMapper, times(2)).selectOne(any());
    }
}
