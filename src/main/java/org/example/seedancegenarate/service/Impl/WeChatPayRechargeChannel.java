package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.RechargeChannelAdapter;
import org.example.seedancegenarate.task.OrderCloseConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeChatPayRechargeChannel implements RechargeChannelAdapter {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RechargeOrderMapper rechargeOrderMapper;
    private final AsyncJobService asyncJobService;

    @Value("${recharge.order-close-delay-seconds:600}")
    private long orderCloseDelaySeconds;
    @Override
    public String channel() {
        return RechargeOrder.CHANNEL_WECHAT;
    }

    @Override
    public void recharge(RechargeCommand command) {
        throw new UnsupportedOperationException("微信为异步支付，请使用 createPendingOrder()");
    }

    @Override
    @Transactional
    public String createPendingOrder(RechargeCommand command) {
        // 1. 幂等检查：如果 requestId 已存在，返回已有订单号
        if (command.requestId() != null){
           RechargeOrder existing = rechargeOrderMapper.selectOne(
                   Wrappers.<RechargeOrder>lambdaQuery()
                           .eq(RechargeOrder::getUserId, command.userId())
                           .eq(RechargeOrder::getRequestId, command.requestId())
                           .eq(RechargeOrder::getChannel, RechargeOrder.CHANNEL_WECHAT)
           );
           if (existing != null) {
               log.info("微信充值订单已存在，幂等返回: orderNo={}, requestId={}",
                       existing.getOrderNo(), command.requestId());
               return existing.getOrderNo();
           }
       }
        // 2. 生成订单号（ALP + 时间戳 + 6位随机）
        String orderNo = generateOrderNo();
        // 3. 创建 PENDING 订单（不入账，等微信异步通知）
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(command.userId());
        order.setChannel(RechargeOrder.CHANNEL_WECHAT);
        order.setAmount(command.amount());
        order.setStatus(RechargeOrder.STATUS_PENDING);
        order.setRequestId(command.requestId());
        order.setReason(command.reason() != null ? command.reason() : "微信充值");
        rechargeOrderMapper.insert(order);
        // 4. 超时自动关闭：延迟作业
        asyncJobService.enqueueDelayed(OrderCloseConsumer.JOB_TYPE_ORDER_CLOSE,
                "order:"+ orderNo,
                "{\"orderNo\":\"" + orderNo + "\"}",
                orderCloseDelaySeconds);
        log.info("创建微信充值订单: orderNo={}, userId={}, amount={}, requestId={}, closeDelaySec={}",
                orderNo, command.userId(), command.amount(), command.requestId(), orderCloseDelaySeconds);
        return orderNo;
    }



    private String generateOrderNo() {
        return "WXP" + System.currentTimeMillis() +
                String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
