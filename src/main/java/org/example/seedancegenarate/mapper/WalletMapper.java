package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.dto.WalletReconcileDiff;
import org.example.seedancegenarate.dto.WalletSpendingDailyRow;
import org.example.seedancegenarate.dto.WalletSpendingModelRow;
import org.example.seedancegenarate.dto.WalletSpendingTotals;
import org.example.seedancegenarate.entity.Wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WalletMapper extends BaseMapper<Wallet> {

    /** 懒建钱包（幂等，撞唯一键自动跳过） */
    @Insert("INSERT IGNORE INTO wallet (user_id, balance, frozen) VALUES (#{userId}, 0.00, 0.00)")
    int insertIgnore(@Param("userId") Long userId);

    /** 余额变动（行锁原子 UPDATE，CAS 语义交给调用方条件） */
    @Update("UPDATE wallet SET balance = balance + #{amount} WHERE user_id = #{userId}")
    int addBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 冻结：可用转冻结，余额不足则 0 行（调用方判定拒绝） */
    @Update("UPDATE wallet SET frozen = frozen + #{amount}, balance = balance - #{amount} "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int freeze(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 结算：冻结转消费（动 frozen，永不失败） */
    @Update("UPDATE wallet SET frozen = frozen - #{amount} WHERE user_id = #{userId} AND frozen >= #{amount}")
    int settle(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 解冻：冻结退回可用 */
    @Update("UPDATE wallet SET frozen = frozen - #{amount}, balance = balance + #{amount} "
            + "WHERE user_id = #{userId} AND frozen >= #{amount}")
    int release(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 消费总额 / 本月消费 / 任务数 / 成功数 */
    WalletSpendingTotals selectSpendingTotals(@Param("userId") Long userId,
                                               @Param("monthStart") LocalDateTime monthStart);

    /** 按模型聚合 SETTLE 消费金额 */
    List<WalletSpendingModelRow> selectSpendingByModel(@Param("userId") Long userId);

    /** 按日聚合 SETTLE 消费金额 */
    List<WalletSpendingDailyRow> selectSpendingByDay(@Param("userId") Long userId,
                                                      @Param("startTime") LocalDateTime startTime);

    /**
     * 对账：流水合计 vs 总资产（balance + frozen）不一致的用户。
     * 口径推导见 {@code WalletReconcileTask}：freeze/settle/release 记账到 total 恒等，只有账务错误才漂移。
     */
    @Select("SELECT w.user_id AS userId, COALESCE(SUM(b.amount), 0) AS ledgerTotal, "
            + "(w.balance + w.frozen) AS walletTotal "
            + "FROM wallet w LEFT JOIN balance_transaction b ON b.user_id = w.user_id "
            + "GROUP BY w.user_id, w.balance, w.frozen "
            + "HAVING ABS(ledgerTotal - walletTotal) > 0.005")
    java.util.List<WalletReconcileDiff> findMismatches();

    /**
     * 对账（冻结维度）：wallet.frozen vs 各笔 hold 的净和。
     * <p>
     * 上面那个总资产恒等式<b>结构上抓不到冻结额被挪用</b> —— release 的 amount 记 0，
     * 只是 frozen→balance 的内部转移，总资产不变，所以「用别人的冻结额退款」在那个口径下永远是平的。
     * 这里按 hold 维度再对一次：freeze +hold，settle / release −hold。
     */
    @Select("SELECT w.user_id AS userId, "
            + "COALESCE(SUM(CASE WHEN b.type = 'FREEZE' THEN b.hold_amount "
            + "                  WHEN b.type IN ('SETTLE', 'RELEASE') THEN -b.hold_amount "
            + "                  ELSE 0 END), 0) AS ledgerTotal, "
            + "w.frozen AS walletTotal "
            + "FROM wallet w LEFT JOIN balance_transaction b ON b.user_id = w.user_id "
            + "GROUP BY w.user_id, w.frozen "
            + "HAVING ABS(ledgerTotal - walletTotal) > 0.005")
    java.util.List<WalletReconcileDiff> findFrozenMismatches();
}
