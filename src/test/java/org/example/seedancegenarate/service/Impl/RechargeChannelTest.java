package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.RechargeChannelAdapter.RechargeCommand;
import org.example.seedancegenarate.service.RechargeChannelRegistry;
import org.example.seedancegenarate.service.RechargeChannelAdapter;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 资金渠道策略：admin 渠道订单唯一（幂等来源）+ 入账调用；注册表路由正确性。
 */
class RechargeChannelTest {

    @Test
    void adminChannelCreatesOrderThenCredits() {
        RechargeOrderMapper orderMapper = mock(RechargeOrderMapper.class);
        WalletService walletService = mock(WalletService.class);
        AdminRechargeChannel channel = new AdminRechargeChannel(orderMapper, walletService);

        channel.recharge(new RechargeCommand(1L, new BigDecimal("10.00"), 9L, "admin", "补发奖励", "test-1"));

        // 订单落库，且 channel_txn_id = order_no（幂等键来源）
        var captor = org.mockito.ArgumentCaptor.forClass(RechargeOrder.class);
        verify(orderMapper).insert(captor.capture());
        RechargeOrder order = captor.getValue();
        assertEquals(RechargeOrder.CHANNEL_ADMIN, order.getChannel());
        assertEquals(RechargeOrder.STATUS_SUCCESS, order.getStatus());
        assertEquals(order.getOrderNo(), order.getChannelTxnId());
        assertEquals("admin", order.getOperatorName());
        assertEquals("补发奖励", order.getReason());
        // 入账走统一账本（ADMIN_CREDIT + ref_order_no = order_no）
        verify(walletService).credit(eq(1L), eq(new BigDecimal("10.00")), argThat(ctx ->
                "ADMIN_CREDIT".equals(ctx.type()) && ctx.refOrderNo() != null));
    }

    @Test
    void adminChannelRejectsNonPositiveAmount() {
        RechargeOrderMapper orderMapper = mock(RechargeOrderMapper.class);
        AdminRechargeChannel channel = new AdminRechargeChannel(orderMapper, mock(WalletService.class));

        assertThrows(IllegalArgumentException.class,
                () -> channel.recharge(new RechargeCommand(1L, new BigDecimal("0"), 9L, "admin", "x", "test-0")));
    }

    @Test
    void registryRoutesByChannelAndRejectsUnknown() {
        RechargeChannelAdapter admin = mock(RechargeChannelAdapter.class);
        when(admin.channel()).thenReturn("admin");
        RechargeChannelAdapter wechat = mock(RechargeChannelAdapter.class);
        when(wechat.channel()).thenReturn("wechat");
        RechargeChannelRegistry registry = new RechargeChannelRegistry(List.of(admin, wechat));

        assertSame(admin, registry.get("admin"));
        assertSame(wechat, registry.get("wechat"));
        assertThrows(IllegalArgumentException.class, () -> registry.get("unknown_channel"));
    }
}
