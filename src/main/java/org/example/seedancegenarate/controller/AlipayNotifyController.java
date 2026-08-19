package org.example.seedancegenarate.controller;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AlipayProperties;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 支付宝支付回调处理
 *
 * 安全机制：
 * 1. RSA2 签名验证（防伪造）
 * 2. 订单金额校验（防篡改）
 * 3. 幂等性保证（防重复入账）
 * 4. 状态机控制（只有 PENDING 才能转 SUCCESS）
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class AlipayNotifyController {

    private final AlipayProperties alipayProperties;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final WalletService walletService;
    private final ResourceLoader resourceLoader;

    private String alipayPublicKey;

    /**
     * 初始化：加载支付宝公钥
     */
    @PostConstruct
    public void init() throws IOException {
        this.alipayPublicKey = loadKeyContent(alipayProperties.getAlipayPublicKeyPath());
        log.info("支付宝回调处理器初始化完成: notifyUrl={}", alipayProperties.getNotifyUrl());
    }

    /**
     * 支付宝异步回调接口
     *
     * POST /api/notify/alipay
     * Content-Type: application/x-www-form-urlencoded
     *
     * 支付宝会发送以下参数：
     * - out_trade_no: 商户订单号
     * - trade_no: 支付宝交易号
     * - trade_status: 交易状态（TRADE_SUCCESS / TRADE_FINISHED）
     * - total_amount: 订单金额
     * - sign: 签名
     *
     * @return "success" 或 "failure"
     */
    @PostMapping("/alipay")
    @Transactional
    public String handleAlipayNotify(@RequestParam Map<String, String> params) {
        log.info("收到支付宝回调: params={}", params);

        try {
            // 1. 验签（防伪造）
            boolean signVerified = verifySign(params);
            if (!signVerified) {
                log.error("支付宝回调验签失败: params={}", params);
                return "failure";
            }

            // 2. 提取关键参数
            String outTradeNo = params.get("out_trade_no");   // 商户订单号
            String tradeNo = params.get("trade_no");          // 支付宝交易号
            String tradeStatus = params.get("trade_status");  // 交易状态
            String totalAmount = params.get("total_amount");  // 订单金额

            log.info("支付宝回调验签成功: outTradeNo={}, tradeNo={}, status={}, amount={}",
                outTradeNo, tradeNo, tradeStatus, totalAmount);

            // 3. 查询订单
            RechargeOrder order = rechargeOrderMapper.selectOne(
                Wrappers.<RechargeOrder>lambdaQuery()
                    .eq(RechargeOrder::getOrderNo, outTradeNo)
                    .eq(RechargeOrder::getChannel, RechargeOrder.CHANNEL_ALIPAY)
            );

            if (order == null) {
                log.error("支付宝回调找不到订单: outTradeNo={}", outTradeNo);
                return "failure";
            }

            // 4. 金额校验（防篡改）
            BigDecimal notifyAmount = new BigDecimal(totalAmount);
            if (order.getAmount().compareTo(notifyAmount) != 0) {
                log.error("支付宝回调金额不匹配: outTradeNo={}, expected={}, actual={}",
                    outTradeNo, order.getAmount(), notifyAmount);
                return "failure";
            }

            // 5. 幂等性检查（防重复入账）
            if (RechargeOrder.STATUS_SUCCESS.equals(order.getStatus())) {
                log.warn("支付宝回调订单已处理，幂等返回: outTradeNo={}", outTradeNo);
                return "success";
            }

            // 6. 状态检查（只有 PENDING 才能转 SUCCESS）
            if (!RechargeOrder.STATUS_PENDING.equals(order.getStatus())) {
                log.error("支付宝回调订单状态异常: outTradeNo={}, status={}",
                    outTradeNo, order.getStatus());
                return "failure";
            }

            // 7. 处理不同的交易状态
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                // 入账 + 更新订单状态
                processSuccessPayment(order, tradeNo);
                log.info("支付宝充值成功: outTradeNo={}, tradeNo={}, userId={}, amount={}",
                    outTradeNo, tradeNo, order.getUserId(), order.getAmount());
                return "success";
            } else if ("TRADE_CLOSED".equals(tradeStatus)) {
                // 交易关闭
                processClosedPayment(order, tradeNo);
                log.info("支付宝交易关闭: outTradeNo={}, tradeNo={}", outTradeNo, tradeNo);
                return "success";
            } else {
                log.warn("支付宝回调状态未处理: outTradeNo={}, status={}", outTradeNo, tradeStatus);
                return "success"; // 其他状态也返回 success，避免支付宝重复回调
            }

        } catch (Exception e) {
            log.error("处理支付宝回调异常", e);
            return "failure";
        }
    }

    /**
     * 处理支付成功：入账 + 更新订单
     */
    private void processSuccessPayment(RechargeOrder order, String tradeNo) {
        // 1. 调用钱包服务入账
        walletService.credit(
            order.getUserId(),
            order.getAmount(),
            WalletService.CreditContext.rechargeCredit(
                order.getOrderNo(),  // bizKey：订单号（幂等键）
                order.getOrderNo(),  // refOrderNo：关联订单号
                "支付宝充值"         // remark：备注
            )
        );

        // 2. 更新订单状态
        order.setStatus(RechargeOrder.STATUS_SUCCESS);
        order.setChannelTxnId(tradeNo);
        rechargeOrderMapper.updateById(order);
    }

    /**
     * 处理交易关闭
     */
    private void processClosedPayment(RechargeOrder order, String tradeNo) {
        order.setStatus(RechargeOrder.STATUS_CLOSED);
        order.setChannelTxnId(tradeNo);
        rechargeOrderMapper.updateById(order);
    }

    /**
     * 验证支付宝签名
     */
    private boolean verifySign(Map<String, String> params) {
        try {
            return AlipaySignature.rsaCheckV1(
                params,
                alipayPublicKey,
                alipayProperties.getCharset(),
                alipayProperties.getSignType()
            );
        } catch (AlipayApiException e) {
            log.error("验证支付宝签名异常", e);
            return false;
        }
    }

    /**
     * 加载密钥文件内容
     */
    private String loadKeyContent(String path) throws IOException {
        try {
            if (path.startsWith("classpath:")) {
                Resource resource = resourceLoader.getResource(path);
                return new String(Files.readAllBytes(Paths.get(resource.getURI())));
            } else {
                return new String(Files.readAllBytes(Paths.get(path)));
            }
        } catch (IOException e) {
            log.error("加载密钥文件失败: path={}", path, e);
            throw new IOException("密钥文件加载失败: " + path, e);
        }
    }
}
