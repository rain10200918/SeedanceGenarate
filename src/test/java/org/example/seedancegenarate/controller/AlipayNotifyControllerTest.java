package org.example.seedancegenarate.controller;

import com.alipay.api.internal.util.AlipaySignature;
import org.example.seedancegenarate.config.AlipayProperties;
import org.example.seedancegenarate.entity.RechargeOrder;
import org.example.seedancegenarate.mapper.RechargeOrderMapper;
import org.example.seedancegenarate.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付宝回调单测（真实 RSA2 签名/验签，不 mock 静态方法 —— 对齐 CURRENT.md A7 钉死的路线）。
 * 本文件当前覆盖 A9（app_id 校验）+ 正常入账基线，A7 的 10 场景可在此基础上扩展。
 * 密钥写盘遵循 D-008：裸 Base64 单行，不带 PEM 头。
 */
class AlipayNotifyControllerTest {

    private static final String APP_ID = "2021000000000001";

    @TempDir
    Path tempDir;

    private AlipayProperties properties;
    private RechargeOrderMapper orderMapper;
    private WalletService walletService;
    private AlipayNotifyController controller;
    private String privateKeyB64;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKeyB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKeyB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        // D-008：密钥文件必须是裸 Base64 单行（X.509 DER），不带 PEM 头
        Path pubFile = tempDir.resolve("alipay_public_key.pem");
        Files.writeString(pubFile, publicKeyB64);

        properties = new AlipayProperties();
        properties.setAppId(APP_ID);
        properties.setAlipayPublicKeyPath(pubFile.toString());

        orderMapper = mock(RechargeOrderMapper.class);
        walletService = mock(WalletService.class);
        controller = new AlipayNotifyController(
                properties, orderMapper, walletService, new DefaultResourceLoader());
        controller.init();
    }

    /** 构造带合法 RSA2 签名的回调参数 */
    private Map<String, String> signedParams(String appId) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", appId);
        params.put("out_trade_no", "R20260821001");
        params.put("trade_no", "2026082122001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "100.00");
        String content = AlipaySignature.getSignContent(params);
        String sign = AlipaySignature.rsaSign(content, privateKeyB64, "UTF-8", "RSA2");
        params.put("sign", sign);
        params.put("sign_type", "RSA2");
        return params;
    }

    private RechargeOrder pendingOrder() {
        RechargeOrder order = new RechargeOrder();
        order.setUserId(42L);
        order.setOrderNo("R20260821001");
        order.setAmount(new BigDecimal("100.00"));
        order.setStatus(RechargeOrder.STATUS_PENDING);
        return order;
    }

    @Test
    void rejectsMismatchedAppIdBeforeTouchingDb() throws Exception {
        // 测什么：验签通过但 app_id 与配置不符（跨应用重放）→ failure，且不查单、不入账（A9 原文语义）
        // 怎么算红：selectOne 或 credit 被调用 —— 说明 app_id 校验缺失或位置在 DB 操作之后，
        //          别家应用签名合法的通知能驱动我方订单查询/入账链路
        String result = controller.handleAlipayNotify(signedParams("9999000000009999"));

        assertEquals("failure", result);
        verify(orderMapper, never()).selectOne(any());
        verify(walletService, never()).credit(any(), any(), any());
    }

    @Test
    void creditsPendingOrderWhenAppIdMatches() throws Exception {
        // 测什么：app_id 匹配 + TRADE_SUCCESS + PENDING 订单 → success 并入账一次（正常路径不被新校验误伤）
        // 怎么算红：合法回调被拒 —— 用户付了钱不到账，支付宝重试 24h 后放弃，资损级事故
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        String result = controller.handleAlipayNotify(signedParams(APP_ID));

        assertEquals("success", result);
        verify(walletService).credit(any(), any(), any());
    }

    @Test
    void skipsAppIdCheckWhenConfigBlank() throws Exception {
        // 测什么：配置 app-id 为空时跳过校验（A9 拍板：不误伤未配置环境），流程继续走到订单查询
        // 怎么算红：空配置导致所有回调被拒 —— 未配置 app-id 的环境（沙箱/旧部署）充值全断
        properties.setAppId("");
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        String result = controller.handleAlipayNotify(signedParams("whatever-app-id"));

        assertEquals("success", result);
    }

    @Test
    void rejectsBadSignatureWithoutDbAccess() throws Exception {
        // 测什么：验签失败 → failure 且不触碰 DB（锁定校验顺序：验签最先）
        // 怎么算红：伪造签名的报文进入订单查询 —— 验签层失效或顺序被挪
        Map<String, String> params = signedParams(APP_ID);
        params.put("sign", Base64.getEncoder().encodeToString("forged".getBytes()));

        String result = controller.handleAlipayNotify(params);

        assertEquals("failure", result);
        verify(orderMapper, never()).selectOne(any());
    }
}
