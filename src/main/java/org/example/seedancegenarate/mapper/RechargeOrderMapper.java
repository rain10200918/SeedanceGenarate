package org.example.seedancegenarate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.seedancegenarate.entity.RechargeOrder;

import java.time.LocalDateTime;

@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /**
     * CAS 关单：只有 PENDING 能被关闭。与回调入账（PENDING→SUCCESS）并发时，
     * 行锁 + 条件更新保证先到先赢，另一侧影响 0 行自然跳过。
     */
    @Update("UPDATE recharge_order SET status = 'CLOSED', close_time = #{closeTime} "
            + "WHERE order_no = #{orderNo} AND status = 'PENDING'")
    int closePendingOrder(@Param("orderNo") String orderNo, @Param("closeTime") LocalDateTime closeTime);
}
