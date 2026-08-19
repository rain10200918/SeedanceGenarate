package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.RechargeChannelAdapter;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管理员加钱渠道（channel = admin）：手动核销/线下收款后入账。
 * request_id 是客户端幂等键；order_no 只是内部资金单号，重复提交或并发重放均返回已落库订单语义，绝不双倍到账。
 */
@Component
@RequiredArgsConstructor
public class AdminRechargeChannel implements RechargeChannelAdapter {

    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RechargeOrderMapper rechargeOrderMapper;
    private final WalletService walletService;

    @Override
    public String channel() {
        return RechargeOrder.CHANNEL_ADMIN;
    }

    @Override
    @Transactional
    public void recharge(RechargeCommand command) {
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new IllegalArgumentException("加钱金额必须大于 0");
        }
        String requestId = StringUtils.hasText(command.requestId()) ? command.requestId().trim() : generateOrderNo();
        if (requestId.length() > 128) {
            throw new IllegalArgumentException("充值请求幂等键过长");
        }

        // 锁定同一用户/请求键的已有订单：并发请求会在这里等第一笔提交后再读，避免
        // 「两边都查不到 → 一边撞唯一键 → 返回订单冲突」的假失败响应。
        RechargeOrder existing = findByRequestId(command.userId(), requestId);
        if (existing != null) {
            assertSameRequest(existing, command.amount());
            return;
        }

        // order_no 是随机生成的内部单号；极小概率撞 uk_ro_order_no 时只换单号，
        // 但若 request_id 唯一键撞上，必须返回已落库的第一笔，不得再次入账。
        for (int attempt = 0; attempt < 3; attempt++) {
            RechargeOrder order = newOrder(command, requestId);
            try {
                rechargeOrderMapper.insert(order);
            } catch (DuplicateKeyException e) {
                RechargeOrder winner = findByRequestId(command.userId(), requestId);
                if (winner != null) {
                    assertSameRequest(winner, command.amount());
                    return;
                }
                if (attempt == 2) {
                    throw new IllegalStateException("订单号冲突，请重试", e);
                }
                continue;
            }
            walletService.credit(command.userId(), command.amount(),
                    WalletService.CreditContext.adminCredit(order.getOrderNo(), order.getOrderNo(),
                            command.operatorId(), command.operatorName(), command.reason()));
            return;
        }
    }

    private RechargeOrder findByRequestId(Long userId, String requestId) {
        return rechargeOrderMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<RechargeOrder>lambdaQuery()
                        .eq(RechargeOrder::getUserId, userId)
                        .eq(RechargeOrder::getRequestId, requestId)
                        // locking read sees the winner committed by a concurrent transaction
                        .last("limit 1 for update"));
    }

    private void assertSameRequest(RechargeOrder existing, java.math.BigDecimal amount) {
        if (existing.getAmount() == null || existing.getAmount().compareTo(amount) != 0
                || !RechargeOrder.CHANNEL_ADMIN.equals(existing.getChannel())) {
            throw new IllegalStateException("充值请求幂等键已用于其他订单");
        }
    }

    private RechargeOrder newOrder(RechargeCommand command, String requestId) {
        String orderNo = generateOrderNo();
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(command.userId());
        order.setChannel(RechargeOrder.CHANNEL_ADMIN);
        order.setChannelTxnId(orderNo); // admin 渠道：我方单号即渠道单号
        order.setRequestId(requestId);
        order.setAmount(command.amount());
        order.setStatus(RechargeOrder.STATUS_SUCCESS);
        order.setOperatorId(command.operatorId());
        order.setOperatorName(command.operatorName());
        order.setReason(command.reason());
        order.setPaidAt(LocalDateTime.now());
        return order;
    }

    private String generateOrderNo() {
        return "RC" + LocalDateTime.now().format(ORDER_TIME) + String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
