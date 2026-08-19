package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.RechargeChannelAdapter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * 支付宝充值渠道：异步支付，仅创建 PENDING 订单，不立即入账。
 * 用户支付后支付宝回调 → AlipayNotifyService 验签后调 WalletService 入账。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliPayRechargeChannel implements RechargeChannelAdapter {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RechargeOrderMapper rechargeOrderMapper;

    @Override
    public String channel() {
        return RechargeOrder.CHANNEL_ALIPAY;
    }

    @Override
    public void recharge(RechargeCommand command) {
        throw new UnsupportedOperationException("支付宝为异步支付，请使用 createPendingOrder()");
    }

    @Override
    @Transactional
    public String createPendingOrder(RechargeCommand command) {
        // 1. 幂等检查：如果 requestId 已存在，返回已有订单号
        if (command.requestId() != null) {
            RechargeOrder existing = rechargeOrderMapper.selectOne(
                Wrappers.<RechargeOrder>lambdaQuery()
                    .eq(RechargeOrder::getUserId, command.userId())
                    .eq(RechargeOrder::getRequestId, command.requestId())
                    .eq(RechargeOrder::getChannel, RechargeOrder.CHANNEL_ALIPAY)
            );
            if (existing != null) {
                log.info("支付宝充值订单已存在，幂等返回: orderNo={}, requestId=",
                    existing.getOrderNo(), command.requestId());
                return existing.getOrderNo();
            }
        }

        // 2. 生成订单号（ALP + 时间戳 + 6位随机）
        String orderNo = generateOrderNo();

        // 3. 创建 PENDING 订单（不入账，等回调）
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(command.userId());
        order.setChannel(RechargeOrder.CHANNEL_ALIPAY);
        order.setAmount(command.amount());
        order.setStatus(RechargeOrder.STATUS_PENDING);
        order.setRequestId(command.requestId());
        order.setReason(command.reason() != null ? command.reason() : "支付宝充值");
        // channelTxnId 在回调时填入支付宝交易号

        rechargeOrderMapper.insert(order);

        log.info("创建支付宝充值订单: orderNo={}, userId={}, amount={}, requestId={}",
            orderNo, command.userId(), command.amount(), command.requestId());

        return orderNo;
    }

    /**
     * 生成支付宝订单号：ALP + 毫秒时间戳(13位) + 6位随机数 = 22位
     * 例如: ALP1723987654321123456
     */
    private String generateOrderNo() {
        return "ALP" + System.currentTimeMillis() +
               String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
