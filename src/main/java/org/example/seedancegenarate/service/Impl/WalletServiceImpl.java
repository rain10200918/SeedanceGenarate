package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.dto.WalletSpendingDailyRow;
import org.example.seedancegenarate.dto.WalletSpendingModelRow;
import org.example.seedancegenarate.dto.WalletSpendingSummary;
import org.example.seedancegenarate.dto.WalletSpendingTotals;
import org.example.seedancegenarate.dto.WalletSpendingView;
import org.example.seedancegenarate.entity.BalanceTransaction;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.entity.Wallet;
import org.example.seedancegenarate.mapper.BalanceTransactionMapper;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.mapper.WalletMapper;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 钱包账务实现。核心不变量：
 * <ol>
 *   <li>入账/冻结/结算/解冻全部「先 INSERT 流水（撞唯一键 = 已处理，幂等跳过）→ 再 UPDATE wallet」同事务</li>
 *   <li>并发正确性靠 DB 行锁 + CAS 条件更新（balance &gt;= X / frozen &gt;= X），余额不可能变负</li>
 *   <li>流水 balance_after 在余额更新后回填，SUM(流水) 永远可与 wallet.balance 对账</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletMapper walletMapper;
    private final BalanceTransactionMapper balanceTransactionMapper;
    private final RechargeOrderMapper rechargeOrderMapper;

    @Override
    @Transactional
    public void credit(Long userId, BigDecimal amount, CreditContext ctx) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("入账金额必须大于 0");
        }
        // ① 先插流水：biz_key 唯一约束挡住重复入账（撞键 = 已处理，静默返回，绝不双倍）
        BalanceTransaction bt = new BalanceTransaction();
        bt.setUserId(userId);
        bt.setType(ctx.type());
        bt.setAmount(amount);
        bt.setBizKey(ctx.bizKey());
        bt.setTaskId(ctx.taskId());
        bt.setRefOrderNo(ctx.refOrderNo());
        bt.setOperatorId(ctx.operatorId());
        bt.setOperatorName(ctx.operatorName());
        bt.setRemark(ctx.remark());
        try {
            balanceTransactionMapper.insert(bt);
        } catch (DuplicateKeyException e) {
            log.info("入账幂等跳过（biz_key 已存在）: userId={}, bizKey={}", userId, ctx.bizKey());
            return;
        }
        // ② 余额变更（仅在钱包不存在时按需懒建，避免无谓的 INSERT IGNORE 产生共享锁/间隙锁死锁）
        int rows = walletMapper.addBalance(userId, amount);
        if (rows == 0) {
            walletMapper.insertIgnore(userId);
            rows = walletMapper.addBalance(userId, amount);
            if (rows == 0) {
                throw new IllegalStateException("钱包不存在，入账失败");
            }
        }
        // ③ 回填 balance_after/frozen_after，保证流水可对账
        Wallet wallet = walletMapper.selectOne(byUser(userId));
        if (wallet == null) {
            throw new IllegalStateException("钱包不存在，入账失败");
        }
        balanceTransactionMapper.updateBalanceAfter(bt.getId(), wallet.getBalance(), wallet.getFrozen());
    }

    @Override
    @Transactional
    public boolean freeze(Long userId, BigDecimal amount, Long taskId) {
        if (amount == null || amount.signum() <= 0) {
            return true; // 0 元任务跳过冻结
        }
        String bizKey = "task:" + taskId;
        // 冻结只是 balance → frozen 的内部转移，不改变账户总资产；流水 amount 必须为 0，
        // 否则 SUM(流水.amount) 与 wallet.balance + wallet.frozen 会凭空少一笔冻结额。
        BalanceTransaction bt = insertLedger(userId, BalanceTransaction.TYPE_FREEZE, BigDecimal.ZERO, amount, bizKey, taskId);
        if (bt == null) {
            return true; // 已冻结过（重复提交/重试），视为成功
        }
        int rows = walletMapper.freeze(userId, amount);
        if (rows == 0) {
            // 如果钱包行尚未初始化则尝试补建一次后重试，否则为真实余额不足
            Wallet wallet = walletMapper.selectOne(byUser(userId));
            if (wallet == null) {
                walletMapper.insertIgnore(userId);
                rows = walletMapper.freeze(userId, amount);
            }
            if (rows == 0) {
                // 余额不足：抛异常让事务整体回滚（流水一并回滚），调用方转「余额不足拒绝」
                throw new InsufficientBalanceException();
            }
        }
        Wallet wallet = walletMapper.selectOne(byUser(userId));
        fillBalanceAfter(wallet, bt);
        return true;
    }

    @Override
    @Transactional
    public void settle(Long userId, BigDecimal amount, Long taskId) {
        if (amount == null || amount.signum() <= 0) {
            return; // 0 元任务无冻结可结算
        }
        String bizKey = "task:" + taskId + ":settle";
        // 前置判定：从未冻结过的任务不能去动 frozen —— frozen 是一个池子、不按任务分格，
        // 池子里若恰好有别人的钱，settle 会照扣不误（见 everFrozen 的注释）
        if (!everFrozen(taskId)) {
            // 任务成功了却没冻结过（提交时异常回滚了 FREEZE）：从可用余额直接扣，hold 记 0
            BalanceTransaction bt = insertLedger(userId, BalanceTransaction.TYPE_SETTLE,
                    amount.negate(), BigDecimal.ZERO, bizKey, taskId);
            if (bt == null) {
                return; // 已结算过，幂等跳过
            }
            if (walletMapper.addBalance(userId, amount.negate()) != 1) {
                throw new IllegalStateException("钱包可用余额不足，无法结算: userId=" + userId + ", taskId=" + taskId);
            }
            log.warn("任务未曾冻结，直接从可用余额扣除结算: userId={}, taskId={}, amount={}", userId, taskId, amount);
            fillBalanceAfter(walletMapper.selectOne(byUser(userId)), bt);
            return;
        }

        BalanceTransaction bt = insertLedger(userId, BalanceTransaction.TYPE_SETTLE, amount.negate(), amount, bizKey, taskId);
        if (bt == null) {
            return; // 已结算过，幂等跳过
        }
        if (walletMapper.settle(userId, amount) != 1) {
            // 流水与钱包必须同事务；冻结不足/钱包缺失时回滚流水，交给补偿下一轮重试。
            throw new IllegalStateException("钱包冻结余额不足，无法结算: userId=" + userId + ", taskId=" + taskId);
        }
        Wallet wallet = walletMapper.selectOne(byUser(userId));
        fillBalanceAfter(wallet, bt);
    }

    @Override
    @Transactional
    public void release(Long userId, BigDecimal amount, Long taskId) {
        if (amount == null || amount.signum() <= 0) {
            return; // 0 元任务无冻结可解
        }
        String bizKey = "task:" + taskId + ":release";
        // 前置判定：从未冻结过就没有可退的冻结额。这道检查以前放在「写钱包失败之后」，
        // 而 frozen 是一个不分格的池子 —— 池子里只要有别的任务的钱够数，
        // WHERE frozen >= amount 就会通过、rows==1、检查根本执行不到，
        // 于是把别人的冻结额搬进了 balance。2026-08-21 真实发生（task 744 挪走 1.80）。
        if (!everFrozen(taskId)) {
            // hold 记 0：钱包一分未动，流水不能宣称动过 hold，否则 frozen 维度对账会假阳性
            BalanceTransaction bt = insertLedger(userId, BalanceTransaction.TYPE_RELEASE,
                    BigDecimal.ZERO, BigDecimal.ZERO, bizKey, taskId);
            if (bt == null) {
                return; // 已解冻过，幂等跳过
            }
            log.warn("任务从未成功冻结过，不退冻结额、只记流水收尾: userId={}, taskId={}, amount={}",
                    userId, taskId, amount);
            Wallet wallet = walletMapper.selectOne(byUser(userId));
            if (wallet != null) {
                fillBalanceAfter(wallet, bt);
            }
            return;
        }

        // 解冻是 frozen → balance 的内部转移，不改变账户总资产；净额只记 0。
        BalanceTransaction bt = insertLedger(userId, BalanceTransaction.TYPE_RELEASE, BigDecimal.ZERO, amount, bizKey, taskId);
        if (bt == null) {
            return; // 已解冻过，幂等跳过
        }
        if (walletMapper.release(userId, amount) != 1) {
            // 流水与钱包必须同事务；冻结不足/钱包缺失时回滚流水，交给补偿下一轮重试。
            throw new IllegalStateException("钱包冻结余额不足，无法解冻: userId=" + userId + ", taskId=" + taskId);
        }
        Wallet wallet = walletMapper.selectOne(byUser(userId));
        fillBalanceAfter(wallet, bt);
    }

    /**
     * 该任务是否真的成功冻结过（FREEZE 流水存在）。
     * <p>
     * FREEZE 流水与钱包更新同事务，所以「读得到」等价于「钱真的进了 frozen」。
     */
    private boolean everFrozen(Long taskId) {
        Long count = balanceTransactionMapper.selectCount(
                Wrappers.<BalanceTransaction>lambdaQuery()
                        .eq(BalanceTransaction::getBizKey, "task:" + taskId)
                        .eq(BalanceTransaction::getType, BalanceTransaction.TYPE_FREEZE));
        return count != null && count > 0;
    }

    @Override
    public Wallet getWallet(Long userId) {
        Wallet wallet = walletMapper.selectOne(byUser(userId));
        if (wallet == null) {
            walletMapper.insertIgnore(userId);
            wallet = walletMapper.selectOne(byUser(userId));
        }
        return wallet;
    }

    @Override
    public Page<BalanceTransaction> pageTransactions(Long userId, long current, long size, String type) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        return balanceTransactionMapper.selectUserPage(
                new Page<>(pageCurrent, pageSize), userId, type);
    }

    @Override
    public WalletSpendingSummary spendingSummary(Long userId) {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        WalletSpendingTotals totals = walletMapper.selectSpendingTotals(userId, monthStart);
        if (totals == null) {
            totals = new WalletSpendingTotals();
        }
        List<WalletSpendingModelRow> modelRows = walletMapper.selectSpendingByModel(userId);
        List<WalletSpendingDailyRow> dailyRows = walletMapper.selectSpendingByDay(
                userId, LocalDate.now().minusDays(6).atStartOfDay());
        return new WalletSpendingSummary(
                decimalOrZero(totals.getTotalSpent()),
                decimalOrZero(totals.getMonthSpent()),
                longOrZero(totals.getTaskCount()),
                longOrZero(totals.getSuccessCount()),
                modelRows.stream()
                        .map(row -> new WalletSpendingSummary.ModelSpending(
                                row.getModel(), decimalOrZero(row.getAmount()), longOrZero(row.getTaskCount())))
                        .toList(),
                dailyRows.stream()
                        .map(row -> new WalletSpendingSummary.DailySpending(
                                row.getDate(), decimalOrZero(row.getAmount())))
                        .toList());
    }

    @Override
    public Page<WalletSpendingView> pageSpending(Long userId, long current, long size) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        return balanceTransactionMapper.selectUserSpendingPage(
                new Page<>(pageCurrent, pageSize), userId);
    }

    @Override
    public Page<RechargeOrder> pageRechargeOrders(Long userId, long current, long size) {
        long pageCurrent = Math.max(current, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);
        return rechargeOrderMapper.selectPage(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<RechargeOrder>lambdaQuery()
                        .eq(RechargeOrder::getUserId, userId)
                        .orderByDesc(RechargeOrder::getCreateTime));
    }

    /** 尝试插入流水：返回已插入实体（含生成的自增 id），若 biz_key 已存在则返回 null（幂等跳过） */
    private BalanceTransaction insertLedger(Long userId, String type, BigDecimal amount,
                                            BigDecimal holdAmount, String bizKey, Long taskId) {
        BalanceTransaction bt = new BalanceTransaction();
        bt.setUserId(userId);
        bt.setType(type);
        bt.setAmount(amount);
        bt.setHoldAmount(holdAmount);
        bt.setBizKey(bizKey);
        bt.setTaskId(taskId);
        try {
            balanceTransactionMapper.insert(bt);
            return bt;
        } catch (DuplicateKeyException e) {
            log.info("流水幂等跳过（biz_key 已存在）: userId={}, bizKey={}", userId, bizKey);
            return null;
        }
    }

    private void fillBalanceAfter(Wallet wallet, BalanceTransaction bt) {
        if (wallet == null) {
            throw new IllegalStateException("钱包不存在，无法回填流水快照");
        }
        if (bt == null || bt.getId() == null) {
            return;
        }
        if (balanceTransactionMapper.updateBalanceAfter(bt.getId(), wallet.getBalance(), wallet.getFrozen()) != 1) {
            throw new IllegalStateException("流水余额快照回填失败: id=" + bt.getId() + ", bizKey=" + bt.getBizKey());
        }
        // 每一笔钱的变动都留一行。这里是 credit / freeze / settle / release 四条路径的共同收尾，
        // 一处就能覆盖全部。改动前钱的**正常路径一个字都不打**，只有异常和幂等分支有声音 ——
        // 2026-08 排查 task 764 的冻结额被挪用时，只能靠翻数据库逐行对，日志里什么线索都没有。
        // 一个任务最多产生 2 行（冻结 + 结算/解冻），量可以忽略。
        log.info("钱包变动: userId={}, type={}, amount={}, hold={}, bizKey={}, 变动后 balance={}, frozen={}",
                bt.getUserId(), bt.getType(), bt.getAmount(), bt.getHoldAmount(), bt.getBizKey(),
                wallet.getBalance(), wallet.getFrozen());
    }

    private Wallet walletOf(Long userId) {
        walletMapper.insertIgnore(userId);
        return walletMapper.selectOne(byUser(userId));
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Wallet> byUser(Long userId) {
        return new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Wallet>()
                .eq(Wallet::getUserId, userId);
    }

    private BigDecimal decimalOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long longOrZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 余额不足：由事务回滚整个冻结操作（含已插流水），调用方捕获后转「余额不足拒绝」 */
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException() {
            super("余额不足，请先充值");
        }
    }
}
