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
     * 加载密钥文件内容并规整格式（剔除 PEM 头尾、换行符，并自动转换 PKCS#1 为 PKCS#8）
     */
    private String loadKeyContent(String path) throws IOException {
        try {
            String raw;
            // 支持 classpath: 和绝对路径
            if (path.startsWith("classpath:")) {
                Resource resource = resourceLoader.getResource(path);
                try (java.io.InputStream is = resource.getInputStream()) {
                    raw = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                raw = new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
            }
            return normalizeKey(raw);
        } catch (IOException e) {
            log.error("加载密钥文件失败: path={}", path, e);
            throw new IOException("密钥文件加载失败: " + path, e);
        }
    }

    /**
     * 标准化密钥：
     * 1. 剔除 -----BEGIN ...----- 和 -----END ...-----
     * 2. 剔除所有空白与换行符
     * 3. 若为 PKCS#1 格式，自动转换为 Java / 支付宝 SDK 识别的 PKCS#8 格式
     */
    public static String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        boolean isPkcs1 = rawKey.contains("BEGIN RSA PRIVATE KEY");
        String cleaned = rawKey
                .replaceAll("-----BEGIN [A-Z0-9_ ]+-----", "")
                .replaceAll("-----END [A-Z0-9_ ]+-----", "")
                .replaceAll("\\s+", "")
                .trim();

        if (isPkcs1) {
            try {
                byte[] pkcs1Bytes = java.util.Base64.getDecoder().decode(cleaned);
                byte[] pkcs8Bytes = convertPkcs1ToPkcs8(pkcs1Bytes);
                return java.util.Base64.getEncoder().encodeToString(pkcs8Bytes);
            } catch (Exception e) {
                log.warn("PKCS#1 自动转 PKCS#8 失败，回退原始内容: {}", e.getMessage());
                return cleaned;
            }
        }
        return cleaned;
    }

    /**
     * 将 PKCS#1 RSA 私钥 DER 字节包装为标准 PKCS#8 PrivateKeyInfo 结构
     */
    private static byte[] convertPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        // RSA AlgorithmIdentifier DER 编码 (OID 1.2.840.113549.1.1.1, NULL)
        byte[] rsaAlgorithmId = new byte[]{
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };

        // 构造 OCTET STRING (包含 pkcs1Bytes)
        byte[] octetString = encodeDer(0x04, pkcs1Bytes);

        // 构造 Version (0)
        byte[] version = new byte[]{0x02, 0x01, 0x00};

        // 拼接 Version + AlgorithmId + OctetString
        byte[] inner = new byte[version.length + rsaAlgorithmId.length + octetString.length];
        System.arraycopy(version, 0, inner, 0, version.length);
        System.arraycopy(rsaAlgorithmId, 0, inner, version.length, rsaAlgorithmId.length);
        System.arraycopy(octetString, 0, inner, version.length + rsaAlgorithmId.length, octetString.length);

        // 封装为顶级 SEQUENCE
        return encodeDer(0x30, inner);
    }

    private static byte[] encodeDer(int tag, byte[] data) {
        int length = data.length;
        byte[] lengthBytes;
        if (length < 128) {
            lengthBytes = new byte[]{(byte) length};
        } else if (length < 256) {
            lengthBytes = new byte[]{(byte) 0x81, (byte) length};
        } else if (length < 65536) {
            lengthBytes = new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) (length & 0xff)};
        } else {
            lengthBytes = new byte[]{
                    (byte) 0x83,
                    (byte) (length >> 16),
                    (byte) ((length >> 8) & 0xff),
                    (byte) (length & 0xff)
            };
        }
        byte[] result = new byte[1 + lengthBytes.length + data.length];
        result[0] = (byte) tag;
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.length);
        System.arraycopy(data, 0, result, 1 + lengthBytes.length, data.length);
        return result;
    }
}
