package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.dto.BalanceTransactionView;
import org.example.seedancegenarate.dto.WalletSpendingView;
import org.example.seedancegenarate.entity.BalanceTransaction;

import java.math.BigDecimal;

@Mapper
public interface BalanceTransactionMapper extends BaseMapper<BalanceTransaction> {

    /** 管理端流水分页：联查用户名称，避免前端 N+1 查询 */
    Page<BalanceTransactionView> selectAdminPage(Page<BalanceTransactionView> page,
                                                   @Param("userId") Long userId,
                                                   @Param("type") String type);

    /** 用户自己的余额流水分页 */
    Page<BalanceTransaction> selectUserPage(Page<BalanceTransaction> page,
                                             @Param("userId") Long userId,
                                             @Param("type") String type);

    /** 用户自己的消费明细分页 */
    Page<WalletSpendingView> selectUserSpendingPage(Page<WalletSpendingView> page,
                                                     @Param("userId") Long userId);

    /** 回填变更后可用余额与冻结余额（流水先插、余额后更、再回填） */
    @Update("UPDATE balance_transaction SET balance_after = #{balanceAfter}, frozen_after = #{frozenAfter} WHERE id = #{id}")
    int updateBalanceAfter(@Param("id") Long id,
                           @Param("balanceAfter") BigDecimal balanceAfter,
                           @Param("frozenAfter") BigDecimal frozenAfter);
}
