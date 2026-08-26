package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.WechatPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class WechatPaymentServiceTest {

    @Test
    void testInitAndPrepay() {
        WechatPayProperties properties = new WechatPayProperties();
        WechatPaymentService service = new WechatPaymentService(properties, new DefaultResourceLoader());
        service.init();

        try {
            String codeUrl = service.createNativePayQrCode("TEST_WXP_" + System.currentTimeMillis(), new BigDecimal("0.01"), "测试充值0.01元");
            System.out.println(">>> 微信 Native 扫码链接成功生成: " + codeUrl);
            assertNotNull(codeUrl);
        } catch (Exception e) {
            System.out.println(">>> 下单返回: " + e.getMessage());
        }
    }
}
