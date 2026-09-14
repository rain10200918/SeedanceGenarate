package org.example.seedancegenarate.service;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.VideoTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

/** Coordinates key budget and account money in the caller's database transaction. */
@Service
@RequiredArgsConstructor
public class BillingAuthorizationService {
    private final JdbcTemplate jdbc;
    private final ApiKeyBudgetService budgets;
    private final WalletService wallet;

    @Transactional
    public void freeze(VideoTask task, BigDecimal amount) {
        lockTask(task, amount, "PROCESSING");
        budgets.reserve(task.getApiKeyId(), task.getUserId(), task.getId(), amount);
        wallet.freeze(task.getUserId(), amount, task.getId());
        verifyWalletLedger(task, amount, null);
    }

    @Transactional
    public void settle(VideoTask task, BigDecimal amount) {
        lockTask(task, amount, "SUCCESS");
        var result = budgets.finish(task.getApiKeyId(), task.getUserId(), task.getId(), amount,
                ApiKeyBudgetService.TerminalState.SETTLED);
        if (result != ApiKeyBudgetService.TransitionResult.NO_RECORD) verifyWalletLedger(task, amount, "SETTLE");
        wallet.settle(task.getUserId(), amount, task.getId());
    }

    @Transactional
    public void release(VideoTask task, BigDecimal amount) {
        lockTask(task, amount, "FAILED");
        var result = budgets.finish(task.getApiKeyId(), task.getUserId(), task.getId(), amount,
                ApiKeyBudgetService.TerminalState.RELEASED);
        if (result != ApiKeyBudgetService.TransitionResult.NO_RECORD) verifyWalletLedger(task, amount, "RELEASE");
        wallet.release(task.getUserId(), amount, task.getId());
    }

    /** Existing biz_key is only idempotent if its binding and actual accounting amounts match. */
    private void verifyWalletLedger(VideoTask task, BigDecimal amount, String target) {
        if (amount.signum() == 0) return; // Existing wallet deliberately emits no rows for free tasks.
        String freezeKey = "task:" + task.getId();
        var rows = jdbc.query("SELECT user_id,task_id,type,amount,hold_amount,biz_key FROM balance_transaction "
                        + "WHERE biz_key IN (?,?,?) FOR UPDATE",
                (rs, n) -> new Ledger(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6)),
                freezeKey, freezeKey + ":settle", freezeKey + ":release");
        boolean frozen = false;
        for (var row : rows) {
            boolean isFreeze = freezeKey.equals(row.key());
            String type = isFreeze ? "FREEZE" : row.key().endsWith(":settle") ? "SETTLE" : "RELEASE";
            BigDecimal net = "SETTLE".equals(type) ? amount.negate() : BigDecimal.ZERO;
            if (row.userId() != task.getUserId() || row.taskId() != task.getId()
                    || !type.equals(row.type()) || row.amount() == null || row.hold() == null
                    || row.amount().compareTo(net) != 0 || row.hold().compareTo(amount) != 0
                    || (!isFreeze && !type.equals(target))) {
                throw new IllegalStateException("钱包流水与Key预算授权不一致，保留授权等待核查");
            }
            frozen |= isFreeze;
        }
        if (!frozen) throw new IllegalStateException("Key预算授权缺少对应钱包冻结流水");
    }

    private void lockTask(VideoTask task, BigDecimal amount, String expectedStatus) {
        if (task == null || task.getId() == null || task.getApiKeyId() == null
                || task.getUserId() == null || amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("无效的 API 任务账务请求");
        }
        var rows = jdbc.query("SELECT user_id, api_key_id, status, freeze_amount FROM video_task WHERE id = ? FOR UPDATE",
                (rs, n) -> new TaskIdentity(rs.getLong("user_id"), rs.getLong("api_key_id"),
                        rs.getString("status"), rs.getBigDecimal("freeze_amount")), task.getId());
        if (rows.size() != 1) throw new IllegalStateException("账务任务不存在");
        var actual = rows.get(0);
        if (!Objects.equals(actual.userId(), task.getUserId())
                || !Objects.equals(actual.keyId(), task.getApiKeyId())
                || !expectedStatus.equals(actual.status())
                || (actual.amount() != null && actual.amount().compareTo(amount) != 0)) {
            throw new IllegalStateException("账务任务身份、金额或终态不一致");
        }
    }

    private record TaskIdentity(Long userId, Long keyId, String status, BigDecimal amount) {}
    private record Ledger(long userId, long taskId, String type, BigDecimal amount, BigDecimal hold, String key) {}
}
