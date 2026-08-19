package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.dto.WalletSpendingSummary;
import org.example.seedancegenarate.dto.WalletSpendingView;
import org.example.seedancegenarate.entity.BalanceTransaction;
import org.example.seedancegenarate.entity.Wallet;

import java.math.BigDecimal;

/**
 * 钱包账务。铁律（决策表 D-001/D-002）：
 * 一切金额变动 = 「INSERT balance_transaction（biz_key 唯一约束 = 幂等）→ UPDATE wallet」同一事务；
 * 并发正确性靠 DB 行锁 + CAS 条件更新，不引入 Redis/分布式锁。
 */
public interface WalletService {

    /** 入账（充值/管理员加钱/奖励）：幂等（biz_key 已存在则静默跳过，同事务） */
    void credit(Long userId, BigDecimal amount, CreditContext ctx);

    /**
     * 冻结（任务提交预授权）：余额不足返回 false（0 行），调用方决定拒绝。
     * 幂等：同一 taskId 重复冻结撞 biz_key 直接返回 true（不重复冻）。
     */
    boolean freeze(Long userId, BigDecimal amount, Long taskId);

    /** 结算（任务成功）：冻结转消费，动 frozen 永不失败；重复调用幂等跳过 */
    void settle(Long userId, BigDecimal amount, Long taskId);

    /** 解冻（任务失败/超时）：冻结退回可用；重复调用幂等跳过 */
    void release(Long userId, BigDecimal amount, Long taskId);

    /** 查询钱包（无则懒建返回零钱包） */
    Wallet getWallet(Long userId);

    /** 用户余额流水分页：实现必须按 userId 限定，禁止由调用方传入其他用户 ID */
    Page<BalanceTransaction> pageTransactions(Long userId, long current, long size, String type);

    /** 用户消费概览：消费金额只统计 SETTLE 账本流水 */
    WalletSpendingSummary spendingSummary(Long userId);

    /** 用户消费明细：只查询当前用户 SETTLE 流水，分页 */
    Page<WalletSpendingView> pageSpending(Long userId, long current, long size);

    /** 流水入账上下文 */
    record CreditContext(String type, String bizKey, Long taskId,
                         String refOrderNo, Long operatorId, String operatorName, String remark) {
        public static CreditContext adminCredit(String bizKey, String orderNo, Long operatorId,
                                                String operatorName, String remark) {
            return new CreditContext(BalanceTransaction.TYPE_ADMIN_CREDIT, bizKey, null,
                    orderNo, operatorId, operatorName, remark);
        }

        public static CreditContext rechargeCredit(String bizKey, String orderNo, String remark) {
            return new CreditContext(BalanceTransaction.TYPE_RECHARGE, bizKey, null,
                    orderNo, null, null, remark);
        }
    }
}
