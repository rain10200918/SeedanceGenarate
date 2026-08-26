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
        // 默认：任务确实冻结过（settle/release 的正常前提）。「没冻结过」的用例自己覆盖成 0。
        when(btMapper.selectCount(any())).thenReturn(1L);
    }

    /** 让被测任务表现为「从未成功冻结过」——提交时 FREEZE 事务回滚过 */
    private void neverFrozen() {
        when(btMapper.selectCount(any())).thenReturn(0L);
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

    @Test
    void releaseOfNeverFrozenTaskMustNotTouchFrozen() {
        // 测什么：从未冻结过的任务来解冻时，绝不能去动 frozen —— 哪怕池子里钱够
        // 怎么算红：还是调 walletMapper.release() —— frozen 是不分格的池子，
        //          WHERE frozen >= amount 会拿别的任务的冻结额通过，把别人的钱搬进 balance。
        //          缺口此后永久存在，最终砸在最后一个来解冻的任务头上，它会「冻结余额不足」卡死。
        //          2026-08-21 线上真实发生：task 744 未冻结却解冻 1.80，task 764 因此退不出 2.40
        neverFrozen();
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("990.30"));
        wallet.setFrozen(new BigDecimal("7.20"));   // 池子里有别的任务的钱，够 1.80
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        service.release(1L, new BigDecimal("1.80"), 744L);

        verify(walletMapper, never()).release(any(), any());
        verify(walletMapper, never()).addBalance(any(), any());
    }

    @Test
    void releaseOfNeverFrozenTaskRecordsZeroHold() {
        // 测什么：上面那条被拦下时写的流水，hold_amount 必须是 0
        // 怎么算红：仍按 1.80 记 hold —— 钱包一分没动，流水却宣称动了 1.80 的冻结额，
        //          frozen 维度对账会把这个用户报成异常，真异常反而被噪声淹掉
        neverFrozen();
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("990.30"));
        wallet.setFrozen(new BigDecimal("7.20"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        service.release(1L, new BigDecimal("1.80"), 744L);

        var captor = org.mockito.ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(btMapper).insert(captor.capture());
        assertEquals(BalanceTransaction.TYPE_RELEASE, captor.getValue().getType());
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getHoldAmount()),
                "钱包没动，流水就不能宣称动过 hold");
    }

    @Test
    void settleOfNeverFrozenTaskDeductsFromBalanceNotFrozen() {
        // 测什么：从未冻结过但成功了的任务，从可用余额扣，不许碰 frozen
        // 怎么算红：走 walletMapper.settle() —— 同样会吃掉别的任务的冻结额，
        //          区别只是钱变成了消费而不是退款，缺口一样永久存在
        neverFrozen();
        when(walletMapper.addBalance(eq(1L), eq(new BigDecimal("-1.80")))).thenReturn(1);
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("10.00"));
        wallet.setFrozen(new BigDecimal("7.20"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);

        service.settle(1L, new BigDecimal("1.80"), 744L);

        verify(walletMapper, never()).settle(any(), any());
        verify(walletMapper).addBalance(1L, new BigDecimal("-1.80"));
        var captor = org.mockito.ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(btMapper).insert(captor.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().getHoldAmount()),
                "没冻结过就没有 hold 可动");
    }

    @Test
    void releaseStillThrowsWhenFrozenGenuinelyShort() {
        // 测什么：任务确实冻结过、但池子被历史 bug 挖空时，仍要抛错交给补偿，不许静默吞掉
        // 怎么算红：吞掉异常 —— 用户该退的钱永远退不回来，而且没有任何人知道
        Wallet wallet = new Wallet();
        wallet.setBalance(new BigDecimal("981.01"));
        wallet.setFrozen(new BigDecimal("0.60"));
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        when(walletMapper.release(any(), any())).thenReturn(0);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.release(1L, new BigDecimal("2.40"), 764L));
        assertTrue(e.getMessage().contains("冻结余额不足"), e.getMessage());
    }
}
