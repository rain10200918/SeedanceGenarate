package org.example.seedancegenarate.service;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.ApiKeyBudgetAuthorization;
import org.example.seedancegenarate.dto.ApiKeyBudgetView;
import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** MySQL budget accounting. Caller owns task terminal validation and wallet coordination. */
@Service
public class ApiKeyBudgetService {
    public enum TransitionResult { APPLIED, ALREADY_APPLIED, NO_RECORD }
    public enum TerminalState { SETTLED, RELEASED }

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final JdbcTemplate jdbc;

    public ApiKeyBudgetService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public TransitionResult reserve(long apiKeyId, long userId, long taskId, BigDecimal amount) {
        requireTransaction();
        validateIds(apiKeyId, userId, taskId);
        amount = validAmount(amount, false);
        Key key = lockKey(apiKeyId, userId, false);
        Instant now = databaseNow();
        if (!"ENABLED".equals(key.status())) throw ApiException.apiKeyDisabled();
        if (key.expiresAt() != null && !key.expiresAt().isAfter(now.atZone(SHANGHAI).toLocalDateTime())) {
            throw ApiException.apiKeyExpired();
        }
        // Insert before any missing-row locking read: RR gap locks on different keys can deadlock.
        // A persisted per-insertion token distinguishes new authorization without affected-row semantics.
        String month = YearMonth.from(now.atZone(SHANGHAI)).toString();
        String insertionToken = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO api_key_budget_authorization "
                        + "(task_id,api_key_id,user_id,period,amount,state,insertion_token) "
                        + "VALUES (?,?,?,?,?,'RESERVED',?) ON DUPLICATE KEY UPDATE task_id=task_id",
                taskId, apiKeyId, userId, month, amount, insertionToken);
        Hold existing = hold(taskId);
        if (existing == null) throw BusinessException.conflict("预算授权写入失败");
        checkBinding(existing, apiKeyId, userId, amount);
        if (!"RESERVED".equals(existing.state())) throw BusinessException.conflict("预算授权已终结");
        if (!insertionToken.equals(existing.insertionToken())) {
            return TransitionResult.ALREADY_APPLIED;
        }
        Config config = config(apiKeyId);
        Period current = initializedPeriod(apiKeyId, month);
        if (amount.signum() > 0 && config.limit() != null
                && current.consumed().add(current.reserved()).add(amount).compareTo(config.limit()) > 0) {
            throw new ApiException("API_KEY_SPENDING_LIMIT_EXCEEDED", HttpStatus.FORBIDDEN,
                    "API Key 月度消费预算不足");
        }
        ledger(existing, "RESERVE", "RESERVED");
        if (amount.signum() > 0) {
            changed(jdbc.update("UPDATE api_key_budget_period SET reserved=reserved+? "
                    + "WHERE api_key_id=? AND period=?", amount, apiKeyId, month));
        }
        return TransitionResult.APPLIED;
    }

    /** Facade must verify durable task terminal state before calling, including NO_RECORD cases. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransitionResult finish(long apiKeyId, long userId, long taskId,
                                   BigDecimal expectedAmount, TerminalState target) {
        requireTransaction();
        validateIds(apiKeyId, userId, taskId);
        expectedAmount = validAmount(expectedAmount, false);
        if (target == null) throw BusinessException.badRequest("预算目标状态不能为空");
        lockKey(apiKeyId, userId, false);
        Hold current = hold(taskId);
        if (current == null) return TransitionResult.NO_RECORD;
        checkBinding(current, apiKeyId, userId, expectedAmount);
        if (target.name().equals(current.state())) return TransitionResult.ALREADY_APPLIED;
        if (!"RESERVED".equals(current.state())) throw BusinessException.conflict("预算授权终态冲突");
        lockedPeriod(apiKeyId, current.month());
        changed(jdbc.update("UPDATE api_key_budget_authorization SET state=? WHERE task_id=? AND state='RESERVED'",
                target.name(), taskId));
        ledger(current, "FINISH", target.name());
        BigDecimal spent = target == TerminalState.SETTLED ? current.amount() : BigDecimal.ZERO;
        if (current.amount().signum() > 0) {
            changed(jdbc.update("UPDATE api_key_budget_period SET reserved=reserved-?, consumed=consumed+? "
                            + "WHERE api_key_id=? AND period=? AND reserved>=?",
                    current.amount(), spent, current.keyId(), current.month(), current.amount()));
        }
        return TransitionResult.APPLIED;
    }

    @Transactional
    public ApiKeyBudgetView query(long actorUserId, long apiKeyId) {
        authorizeActor(actorUserId);
        lockKey(apiKeyId, actorUserId, UserContext.isAdmin());
        return view(apiKeyId, config(apiKeyId));
    }

    @Transactional
    public ApiKeyBudgetView setLimit(long actorUserId, long apiKeyId, BigDecimal limit, long expectedVersion) {
        authorizeActor(actorUserId);
        limit = validAmount(limit, true);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw BusinessException.badRequest("预算版本无效");
        }
        lockKey(apiKeyId, actorUserId, UserContext.isAdmin());
        Config current = config(apiKeyId);
        if (current.version() != expectedVersion) throw BusinessException.conflict("预算配置已更新，请刷新");
        if (!UserContext.isAdmin() && current.limit() != null
                && (limit == null || limit.compareTo(current.limit()) > 0)) {
            throw BusinessException.forbidden("只能收紧预算");
        }
        changed(jdbc.update("UPDATE api_key_budget_config SET limit_amount=?, version=version+1 "
                + "WHERE api_key_id=? AND version=?", limit, apiKeyId, expectedVersion));
        return view(apiKeyId, new Config(limit, expectedVersion + 1));
    }

    /** Internal bounded cursor scan; callers must reload/lock Task and invoke the billing facade. */
    @Transactional
    public List<ApiKeyBudgetAuthorization> findReserved(long afterTaskId, int limit) {
        if (afterTaskId < 0 || limit < 1 || limit > 1000) throw BusinessException.badRequest("预算扫描范围无效");
        return jdbc.query("SELECT api_key_id,user_id,task_id,period,amount FROM api_key_budget_authorization "
                        + "WHERE state='RESERVED' AND task_id>? ORDER BY task_id LIMIT ?",
                (rs, n) -> new ApiKeyBudgetAuthorization(rs.getLong("api_key_id"), rs.getLong("user_id"),
                        rs.getLong("task_id"), rs.getString("period"), rs.getBigDecimal("amount")), afterTaskId, limit);
    }

    /** Epoch seconds are database UTC, independent of JVM and JDBC session timezone. H2 MySQL supports this SQL. */
    protected Instant databaseNow() {
        Long seconds = jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class);
        if (seconds == null) throw new IllegalStateException("数据库时间不可用");
        return Instant.ofEpochSecond(seconds);
    }

    private ApiKeyBudgetView view(long keyId, Config config) {
        YearMonth month = YearMonth.from(databaseNow().atZone(SHANGHAI));
        Period value = initializedPeriod(keyId, month.toString());
        BigDecimal remaining = config.limit() == null ? null
                : config.limit().subtract(value.consumed()).subtract(value.reserved()).max(BigDecimal.ZERO);
        return new ApiKeyBudgetView(keyId, config.limit(), value.consumed(), value.reserved(), remaining,
                month.toString(), month.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toOffsetDateTime(),
                "CNY", config.version());
    }

    private Key lockKey(long keyId, long ownerId, boolean admin) {
        requireTransaction();
        validateIds(keyId, ownerId);
        String sql = "SELECT user_id,status,expires_at FROM api_key WHERE id=?"
                + (admin ? "" : " AND user_id=?") + " FOR UPDATE";
        Object[] args = admin ? new Object[]{keyId} : new Object[]{keyId, ownerId};
        List<Key> owners = jdbc.query(sql,
                (rs, n) -> new Key(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class)), args);
        if (owners.isEmpty()) throw BusinessException.notFound("API Key 不存在");
        return owners.get(0);
    }

    private Config config(long keyId) {
        jdbc.update("INSERT INTO api_key_budget_config(api_key_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE api_key_id=api_key_id", keyId);
        List<Config> rows = jdbc.query("SELECT limit_amount,version FROM api_key_budget_config "
                        + "WHERE api_key_id=? FOR UPDATE",
                (rs, n) -> new Config(rs.getBigDecimal(1), rs.getLong(2)), keyId);
        if (rows.isEmpty()) throw BusinessException.conflict("预算配置写入失败");
        return rows.get(0);
    }

    private Period initializedPeriod(long keyId, String month) {
        jdbc.update("INSERT INTO api_key_budget_period(api_key_id,period) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE api_key_id=api_key_id", keyId, month);
        List<Period> rows = jdbc.query("SELECT period,consumed,reserved FROM api_key_budget_period "
                + "WHERE api_key_id=? AND period=? FOR UPDATE", (rs, n) -> period(rs), keyId, month);
        if (rows.isEmpty()) throw BusinessException.conflict("预算周期写入失败");
        return rows.get(0);
    }

    private void lockedPeriod(long keyId, String month) {
        List<Period> rows = jdbc.query("SELECT period,consumed,reserved FROM api_key_budget_period "
                + "WHERE api_key_id=? AND period=? FOR UPDATE", (rs, n) -> period(rs), keyId, month);
        if (rows.isEmpty()) throw BusinessException.conflict("预算周期账目缺失");
    }

    private Period period(ResultSet rs) throws SQLException {
        return new Period(rs.getString("period"), rs.getBigDecimal("consumed"), rs.getBigDecimal("reserved"));
    }

    private Hold hold(long taskId) {
        List<Hold> rows = jdbc.query("SELECT task_id,api_key_id,user_id,period,amount,state,insertion_token "
                        + "FROM api_key_budget_authorization WHERE task_id=? FOR UPDATE",
                (rs, n) -> new Hold(rs.getLong("task_id"), rs.getLong("api_key_id"), rs.getLong("user_id"),
                        rs.getString("period"), rs.getBigDecimal("amount"), rs.getString("state"),
                        rs.getString("insertion_token")), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ledger(Hold hold, String phase, String state) {
        jdbc.update("INSERT INTO api_key_budget_ledger(task_id,phase,state,api_key_id,user_id,period,amount) "
                        + "VALUES (?,?,?,?,?,?,?)", hold.taskId(), phase, state, hold.keyId(), hold.userId(),
                hold.month(), hold.amount());
    }

    private void checkBinding(Hold hold, long keyId, long userId, BigDecimal amount) {
        if (hold.keyId() != keyId || hold.userId() != userId || hold.amount().compareTo(amount) != 0) {
            throw BusinessException.conflict("预算授权身份或金额不匹配");
        }
    }

    private void authorizeActor(long actorUserId) {
        if (UserContext.getUserId() == null) throw BusinessException.unauthorized("请先登录");
        if (!Objects.equals(UserContext.getUserId(), actorUserId)) throw BusinessException.notFound("API Key 不存在");
    }

    private BigDecimal validAmount(BigDecimal amount, boolean nullable) {
        if (amount == null && nullable) return null;
        if (amount == null || amount.signum() < 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw BusinessException.badRequest("金额必须为0至9999999999.99且最多两位小数");
        }
        return amount.setScale(2);
    }

    private void validateIds(long... ids) {
        for (long id : ids) if (id <= 0) throw BusinessException.badRequest("预算关联ID无效");
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("预算变更必须处于可写事务内");
        }
    }

    private void changed(int rows) {
        if (rows != 1) throw BusinessException.conflict("预算账目发生冲突");
    }

    private record Config(BigDecimal limit, long version) {}
    private record Key(long ownerId, String status, LocalDateTime expiresAt) {}
    private record Period(String month, BigDecimal consumed, BigDecimal reserved) {}
    private record Hold(long taskId, long keyId, long userId, String month, BigDecimal amount,
                        String state, String insertionToken) {}
}
