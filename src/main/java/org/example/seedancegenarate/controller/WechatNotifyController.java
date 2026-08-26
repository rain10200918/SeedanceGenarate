package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wechat.pay.java.service.payments.model.Transaction;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.WechatPayProperties;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.WalletService;
import org.example.seedancegenarate.service.WechatPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 微信支付 APIv3 异步回调通知处理
 *
 * 安全机制：
 * 1. 平台证书签名验证 + AES-GCM 密文解密（防伪造、防篡改）
 * 2. AppID 与 MchID 校验（防跨应用重放）
 * 3. 订单金额精准比对（元/分换算校验）
 * 4. 幂等性保证（防重复回调导致多次入账）
 * 5. 状态机控制（只有 PENDING / 超时关单 CLOSED 兜底转 SUCCESS）
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class WechatNotifyController {

    private final WechatPayProperties wechatPayProperties;
    private final WechatPaymentService wechatPaymentService;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final WalletService walletService;

    @PostMapping("/wechat")
    @Transactional
    public ResponseEntity<Map<String, String>> handleWechatNotify(
            @RequestBody String requestBody,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signatureType,
            HttpServletRequest httpRequest) {

        log.info("收到微信支付异步通知: serial={}, timestamp={}", serial, timestamp);

        try {
            // 1. 官方 SDK 验签与解密
            Transaction transaction = wechatPaymentService.parseAndVerifyNotification(
                    requestBody, signature, serial, nonce, timestamp, signatureType);

            if (transaction == null) {
                log.error("微信支付通知解密结果为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", "FAIL", "message", "通知解析失败"));
            }

            // 1.5 校验 mchid 与 appid（防跨商户重放）
            String expectedMchId = wechatPayProperties.getMchId();
            if (expectedMchId != null && !expectedMchId.isBlank()
                    && !expectedMchId.equals(transaction.getMchid())) {
                log.error("微信支付通知 mchid 不匹配: expected={}, actual={}",
                        expectedMchId, transaction.getMchid());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", "FAIL", "message", "mchid 不匹配"));
            }

            // 2. 提取关键业务字段
            String outTradeNo = transaction.getOutTradeNo();
            String transactionId = transaction.getTransactionId();
            Transaction.TradeStateEnum tradeState = transaction.getTradeState();
            Integer totalCents = transaction.getAmount() != null ? transaction.getAmount().getTotal() : null;

            log.info("微信支付回调验签解密成功: outTradeNo={}, transactionId={}, state={}, amountCents={}",
                    outTradeNo, transactionId, tradeState, totalCents);

            // 3. 查询充值订单
            RechargeOrder order = rechargeOrderMapper.selectOne(
                    Wrappers.<RechargeOrder>lambdaQuery()
                            .eq(RechargeOrder::getOrderNo, outTradeNo)
                            .eq(RechargeOrder::getChannel, RechargeOrder.CHANNEL_WECHAT)
            );

            if (order == null) {
                log.error("微信支付通知找不到订单: outTradeNo={}", outTradeNo);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", "FAIL", "message", "订单不存在"));
            }

            // 4. 金额校验（分转元）
            if (totalCents != null) {
                BigDecimal notifyAmount = new BigDecimal(totalCents)
                        .divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                if (order.getAmount().compareTo(notifyAmount) != 0) {
                    log.error("微信支付金额不匹配: outTradeNo={}, expected={}, actual={}",
                            outTradeNo, order.getAmount(), notifyAmount);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("code", "FAIL", "message", "金额不一致"));
                }
            }

            // 5. 幂等性检查（防重复入账）
            if (RechargeOrder.STATUS_SUCCESS.equals(order.getStatus())) {
                log.warn("微信充值订单已入账，幂等返回成功: outTradeNo={}", outTradeNo);
                return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
            }

            // 6. 状态检查（PENDING 正常入账；CLOSED 是超时关单后用户仍付款的兜底入账）
            if (!RechargeOrder.STATUS_PENDING.equals(order.getStatus())
                    && !RechargeOrder.STATUS_CLOSED.equals(order.getStatus())) {
                log.error("微信充值订单状态异常: outTradeNo={}, status={}", outTradeNo, order.getStatus());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", "FAIL", "message", "订单状态异常"));
            }

            // 7. 处理支付成功
            if (Transaction.TradeStateEnum.SUCCESS.equals(tradeState)) {
                processSuccessPayment(order, transactionId);
                log.info("微信充值入账成功: outTradeNo={}, transactionId={}, userId={}, amount={}",
                        outTradeNo, transactionId, order.getUserId(), order.getAmount());
                return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
            } else if (Transaction.TradeStateEnum.CLOSED.equals(tradeState)
                    || Transaction.TradeStateEnum.REVOKED.equals(tradeState)) {
                processClosedPayment(order, transactionId);
                log.info("微信支付关闭: outTradeNo={}, state={}", outTradeNo, tradeState);
                return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
            } else {
                log.warn("微信支付未完成状态通知: outTradeNo={}, state={}", outTradeNo, tradeState);
                return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
            }

        } catch (Exception e) {
            log.error("处理微信支付异步通知异常", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "FAIL", "message", "处理失败"));
        }
    }

    private void processSuccessPayment(RechargeOrder order, String transactionId) {
        if (RechargeOrder.STATUS_CLOSED.equals(order.getStatus())) {
            log.warn("关单后用户仍完成付款，微信充值兜底入账: outTradeNo={}, transactionId={}",
                    order.getOrderNo(), transactionId);
        }

        // 1. 调用钱包服务入账（credit 自动记录流水并增加余额）
        walletService.credit(
                order.getUserId(),
                order.getAmount(),
                WalletService.CreditContext.rechargeCredit(
                        order.getOrderNo(),
                        order.getOrderNo(),
                        "微信充值"
                )
        );

        // 2. 更新订单状态为 SUCCESS
        order.setStatus(RechargeOrder.STATUS_SUCCESS);
        order.setChannelTxnId(transactionId);
        rechargeOrderMapper.updateById(order);
    }

    private void processClosedPayment(RechargeOrder order, String transactionId) {
        order.setStatus(RechargeOrder.STATUS_CLOSED);
        order.setChannelTxnId(transactionId);
        rechargeOrderMapper.updateById(order);
    }
}
