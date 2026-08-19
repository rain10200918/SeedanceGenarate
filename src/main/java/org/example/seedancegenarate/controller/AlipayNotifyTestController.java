package org.example.seedancegenarate.controller;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AlipayProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝回调测试工具（仅开发环境使用）
 *
 * 用于本地测试回调逻辑，无需真实支付
 *
 * ⚠️ 生产环境必须禁用此 Controller！
 */
@Slf4j
@RestController
@RequestMapping("/api/test/alipay")
@RequiredArgsConstructor
public class AlipayNotifyTestController {

    private final AlipayProperties alipayProperties;
    private final ResourceLoader resourceLoader;

    private String appPrivateKey;

    @PostConstruct
    public void init() throws IOException {
        this.appPrivateKey = loadKeyContent(alipayProperties.getAppPrivateKeyPath());
        log.warn("⚠️ 支付宝回调测试工具已启用，生产环境请禁用！");
    }

    /**
     * 模拟支付宝回调（支付成功）
     *
     * POST /api/test/alipay/mock-notify
     * {
     *   "outTradeNo": "ALP1723987654321123456",
     *   "totalAmount": "100.00"
     * }
     *
     * 返回带签名的完整回调参数，可直接发给 /api/notify/alipay
     */
    @PostMapping("/mock-notify")
    public Map<String, String> mockNotify(@RequestBody MockNotifyRequest request) {
        try {
            // 1. 构造回调参数
            Map<String, String> params = new HashMap<>();
            params.put("gmt_create", "2024-01-15 12:00:00");
            params.put("gmt_payment", "2024-01-15 12:00:05");
            params.put("notify_time", "2024-01-15 12:00:06");
            params.put("notify_type", "trade_status_sync");
            params.put("notify_id", "mock_notify_" + System.currentTimeMillis());
            params.put("app_id", alipayProperties.getAppId());
            params.put("charset", alipayProperties.getCharset());
            params.put("version", "1.0");
            params.put("sign_type", alipayProperties.getSignType());
            params.put("out_trade_no", request.outTradeNo);
            params.put("trade_no", "2024011522001234567890123456");
            params.put("trade_status", "TRADE_SUCCESS");
            params.put("total_amount", request.totalAmount);
            params.put("receipt_amount", request.totalAmount);
            params.put("buyer_pay_amount", request.totalAmount);
            params.put("subject", "Seedance 账户充值");
            params.put("body", "充值订单");
            params.put("buyer_id", "2088000000000000");
            params.put("seller_id", "2088000000000000");

            // 2. 生成签名（注意：rsaSign 第一个参数需要是排序后的参数字符串，不是 Map）
            String content = AlipaySignature.getSignContent(params);
            String sign = AlipaySignature.rsa256Sign(
                content,
                appPrivateKey,
                alipayProperties.getCharset()
            );
            params.put("sign", sign);

            log.info("生成模拟回调参数: outTradeNo={}, amount={}",
                request.outTradeNo, request.totalAmount);

            return params;

        } catch (AlipayApiException e) {
            log.error("生成模拟回调失败", e);
            throw new RuntimeException("生成签名失败", e);
        }
    }

    /**
     * 一键测试：创建订单 + 模拟回调
     *
     * POST /api/test/alipay/quick-test
     * {
     *   "userId": 1,
     *   "amount": "100.00"
     * }
     */
    @PostMapping("/quick-test")
    public QuickTestResponse quickTest(@RequestBody QuickTestRequest request) {
        // 这里需要调用你的 AlipayRechargeController 创建订单
        // 然后自动触发回调
        // 为了简化，这里只返回使用说明
        return new QuickTestResponse(
            "请按以下步骤测试：",
            "1. 调用 POST /api/recharge/alipay/create 创建订单，获取 orderNo",
            "2. 调用 POST /api/test/alipay/mock-notify 生成回调参数",
            "3. 把生成的参数发送给 POST /api/notify/alipay",
            "4. 检查订单状态是否变为 SUCCESS"
        );
    }

    private String loadKeyContent(String path) throws IOException {
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            return new String(Files.readAllBytes(Paths.get(resource.getURI())));
        } else {
            return new String(Files.readAllBytes(Paths.get(path)));
        }
    }

    record MockNotifyRequest(String outTradeNo, String totalAmount) {}
    record QuickTestRequest(Long userId, String amount) {}
    record QuickTestResponse(String... steps) {}
}
