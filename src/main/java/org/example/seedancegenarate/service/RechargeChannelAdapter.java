package org.example.seedancegenarate.service;

import java.math.BigDecimal;

/**
 * 资金流入渠道策略（决策表 D-004）：充值/加钱统一走 recharge_order + 入账链路，
 * 渠道只负责「生成订单并幂等入账」。新增渠道 = 新增一个实现，账务层零改动。
 * 当前实现：{@code admin}（管理员加钱/线下核销）；wechat / alipay 第二批。
 */
public interface RechargeChannelAdapter {

    /** 渠道标识（recharge_order.channel） */
    String channel();

    /**
     * 同步入账：生成订单 → 幂等入账（同一事务）。
     * admin 渠道使用此方法；异步支付渠道（支付宝/微信）不支持，应使用 {@link #createPendingOrder}。
     */
    void recharge(RechargeCommand command);

    /**
     * 异步支付渠道专用：创建待支付订单（PENDING 状态），返回订单号供生成支付链接。
     * 同步渠道（admin）不支持此方法。
     *
     * @return 订单号（例如 ALP1723987654321123456）
     * @throws UnsupportedOperationException 同步渠道调用时抛出
     */
    default String createPendingOrder(RechargeCommand command) {
        throw new UnsupportedOperationException(channel() + " 渠道不支持异步支付");
    }

    /** 入账命令 */
    record RechargeCommand(Long userId, BigDecimal amount,
                           Long operatorId, String operatorName, String reason,
                           String requestId) {
        public RechargeCommand(Long userId, BigDecimal amount,
                               Long operatorId, String operatorName, String reason) {
            this(userId, amount, operatorId, operatorName, reason, null);
        }
    }
}
