package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.dto.WalletReconcileDiff;
import org.example.seedancegenarate.mapper.WalletMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 钱包每日对账：SUM(balance_transaction.amount) vs wallet.balance + wallet.frozen（总资产）。
 * <p>
 * 口径推导（为什么是「总资产」而不是「可用余额」）：
 * <ul>
 *   <li>credit  +X → balance +X，总资产 +X</li>
 *   <li>freeze  amount=0、hold_amount +X → balance -X、frozen +X，总资产不变</li>
 *   <li>settle  amount=-X、hold_amount +X → frozen -X，总资产 -X（可用余额不变）</li>
 *   <li>release amount=0、hold_amount +X → frozen -X、balance +X，总资产不变</li>
 * </ul>
 * 即「流水合计 == 总资产」恒成立；不一致 = 账务错误，必须人工介入。
 * 任务终态缺少 SETTLE/RELEASE 由 {@link TaskReconcileTask} 自动补偿；这里的余额总账差异仍只告警，
 * 不自动猜测调整金额。容差 0.005（DECIMAL 舍入），每日 3 点执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletReconcileTask {

    private final WalletMapper walletMapper;

    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        List<WalletReconcileDiff> diffs = walletMapper.findMismatches();
        if (diffs.isEmpty()) {
            log.info("钱包对账通过：流水合计与总资产一致");
            return;
        }
        diffs.forEach(d -> log.error("钱包对账不一致: userId={}, 流水合计={}, 钱包总资产={}",
                d.getUserId(), d.getLedgerTotal(), d.getWalletTotal()));
    }

    /**
     * 冻结维度对账：上面那个总资产口径抓不到「用别人的冻结额退款」这类错
     * （release 的 amount 记 0，总资产不变，永远是平的）。
     * 2026-08-21 就是这样漏过去的：task 744 未冻结却解冻 1.80，总资产对账全程绿灯。
     */
    @Scheduled(cron = "0 10 3 * * ?")
    public void reconcileFrozen() {
        List<WalletReconcileDiff> diffs = walletMapper.findFrozenMismatches();
        if (diffs.isEmpty()) {
            log.info("钱包冻结额对账通过：frozen 与各笔 hold 净和一致");
            return;
        }
        diffs.forEach(d -> log.error("钱包冻结额对账不一致: userId={}, hold 净和={}, wallet.frozen={} "
                        + "（有任务的冻结额被挪用或流水虚记 hold，需人工核对）",
                d.getUserId(), d.getLedgerTotal(), d.getWalletTotal()));
    }
}
