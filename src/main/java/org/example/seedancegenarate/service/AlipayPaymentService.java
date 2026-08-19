package org.example.seedancegenarate.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AlipayProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 支付宝支付服务
 * 负责生成支付链接、二维码等
 */
@Slf4j
@Service
public class AlipayPaymentService {

    private final AlipayProperties properties;
    private final ResourceLoader resourceLoader;
    private AlipayClient alipayClient;

    public AlipayPaymentService(AlipayProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 初始化支付宝客户端
     */
    @PostConstruct
    public void init() throws IOException {
        String appPrivateKey = loadKeyContent(properties.getAppPrivateKeyPath());
        String alipayPublicKey = loadKeyContent(properties.getAlipayPublicKeyPath());

        this.alipayClient = new DefaultAlipayClient(
            properties.getActualGatewayUrl(),
            properties.getAppId(),
            appPrivateKey,
            properties.getFormat(),
            properties.getCharset(),
            alipayPublicKey,
            properties.getSignType()
        );

        log.info("支付宝客户端初始化完成: appId={}, gateway={}, sandbox={}",
            properties.getAppId(), properties.getActualGatewayUrl(), properties.isSandbox());
    }

    /**
     * 创建 PC 网站支付链接（电脑端）
     *
     * @param orderNo 订单号
     * @param amount 金额
     * @param subject 商品标题
     * @return 支付页面 URL（跳转用）
     */
    public String createPagePayUrl(String orderNo, BigDecimal amount, String subject) {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(properties.getNotifyUrl());
        request.setReturnUrl(properties.getReturnUrl());

        // 业务参数
        String bizContent = String.format(
            "{\"out_trade_no\":\"%s\",\"product_code\":\"FAST_INSTANT_TRADE_PAY\"," +
            "\"total_amount\":\"%s\",\"subject\":\"%s\"}",
            orderNo, amount.toString(), subject
        );
        request.setBizContent(bizContent);

        try {
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
            if (response.isSuccess()) {
                log.info("生成 PC 支付链接成功: orderNo={}, amount={}", orderNo, amount);
                return response.getBody(); // 返回 HTML form，可直接渲染或提取 URL
            } else {
                log.error("生成 PC 支付链接失败: orderNo={}, code={}, msg={}",
                    orderNo, response.getCode(), response.getMsg());
                throw new RuntimeException("支付宝下单失败: " + response.getMsg());
            }
        } catch (AlipayApiException e) {
            log.error("调用支付宝 API 失败: orderNo={}", orderNo, e);
            throw new RuntimeException("支付宝 API 调用失败", e);
        }
    }

    /**
     * 创建手机网站支付链接（H5）
     *
     * @param orderNo 订单号
     * @param amount 金额
     * @param subject 商品标题
     * @return 支付页面 URL
     */
    public String createWapPayUrl(String orderNo, BigDecimal amount, String subject) {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setNotifyUrl(properties.getNotifyUrl());
        request.setReturnUrl(properties.getReturnUrl());

        String bizContent = String.format(
            "{\"out_trade_no\":\"%s\",\"product_code\":\"QUICK_WAP_WAY\"," +
            "\"total_amount\":\"%s\",\"subject\":\"%s\"}",
            orderNo, amount.toString(), subject
        );
        request.setBizContent(bizContent);

        try {
            AlipayTradeWapPayResponse response = alipayClient.pageExecute(request);
            if (response.isSuccess()) {
                log.info("生成 H5 支付链接成功: orderNo={}, amount={}", orderNo, amount);
                return response.getBody();
            } else {
                log.error("生成 H5 支付链接失败: orderNo={}, code={}, msg={}",
                    orderNo, response.getCode(), response.getMsg());
                throw new RuntimeException("支付宝下单失败: " + response.getMsg());
            }
        } catch (AlipayApiException e) {
            log.error("调用支付宝 API 失败: orderNo={}", orderNo, e);
            throw new RuntimeException("支付宝 API 调用失败", e);
        }
    }

    /**
     * 创建扫码支付二维码内容（当面付）
     *
     * @param orderNo 订单号
     * @param amount 金额
     * @param subject 商品标题
     * @return 二维码内容（字符串，需前端生成二维码图片）
     */
    public String createQrCode(String orderNo, BigDecimal amount, String subject) {
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        request.setNotifyUrl(properties.getNotifyUrl());

        String bizContent = String.format(
            "{\"out_trade_no\":\"%s\",\"total_amount\":\"%s\",\"subject\":\"%s\"}",
            orderNo, amount.toString(), subject
        );
        request.setBizContent(bizContent);

        try {
            AlipayTradePrecreateResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                log.info("生成扫码支付二维码成功: orderNo={}, amount={}, qrCode={}",
                    orderNo, amount, response.getQrCode());
                return response.getQrCode();
            } else {
                log.error("生成扫码支付二维码失败: orderNo={}, code={}, msg={}",
                    orderNo, response.getCode(), response.getMsg());
                throw new RuntimeException("支付宝下单失败: " + response.getMsg());
            }
        } catch (AlipayApiException e) {
            log.error("调用支付宝 API 失败: orderNo={}", orderNo, e);
            throw new RuntimeException("支付宝 API 调用失败", e);
        }
    }

    /**
     * 统一入口：根据订单创建支付链接
     * 默认使用 H5 支付（兼容移动端和 PC 端）
     */
    public String createPaymentUrl(String orderNo, BigDecimal amount, Long userId) {
        String subject = "Seedance 账户充值";
        return createWapPayUrl(orderNo, amount, subject);
    }

    /**
     * 加载密钥文件内容
     */
    private String loadKeyContent(String path) throws IOException {
        try {
            // 支持 classpath: 和绝对路径
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
