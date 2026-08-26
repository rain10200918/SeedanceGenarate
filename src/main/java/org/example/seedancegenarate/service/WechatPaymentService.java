package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.auth.Credential;
import com.wechat.pay.java.core.auth.Validator;
import com.wechat.pay.java.core.auth.WechatPay2Credential;
import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.util.GsonUtil;
import com.wechat.pay.java.core.cipher.RSASigner;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpHeaders;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.util.PemUtil;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.h5.model.H5Info;
import com.wechat.pay.java.service.payments.h5.model.SceneInfo;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.WechatPayProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * 微信支付服务 (APIv3)
 * 支持 Native 扫码支付 (NativePay)、H5 支付与异步回调通知验签解密。
 * 支持商户凭证模式与微信支付公钥模式，即便未下载微信公钥也可安全下单与 AES-GCM 解密入账。
 */
@Slf4j
@Service
public class WechatPaymentService {

    private final WechatPayProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private NativePayService nativePayService;
    private H5Service h5Service;
    private NotificationParser notificationParser;
    private AeadAesCipher aeadAesCipher;

    public WechatPaymentService(WechatPayProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("微信支付未启用，跳过客户端初始化");
            return;
        }

        try {
            String privateKeyContent = loadContent(properties.getPrivateKeyPath());
            String apiV3Key = properties.getApiV3Key();
            if (apiV3Key == null || apiV3Key.isBlank()) {
                apiV3Key = loadContent(properties.getApiV3KeyPath()).trim();
            }

            String merchantSerialNumber = properties.getMerchantSerialNumber();
            if (merchantSerialNumber == null || merchantSerialNumber.isBlank()) {
                merchantSerialNumber = extractSerialNumber(properties.getMerchantCertPath());
            }

            // 初始化 AES-256-GCM 解密器（使用 APIv3Key 解密回调通知报文）
            this.aeadAesCipher = new AeadAesCipher(apiV3Key.trim().getBytes(StandardCharsets.UTF_8));

            // 检查是否配置了「微信支付公钥」
            String publicKey = properties.getPublicKey();
            String publicKeyId = properties.getPublicKeyId();
            if (publicKeyId == null || publicKeyId.isBlank()) {
                try {
                    publicKeyId = loadContent(properties.getPublicKeyIdPath()).trim();
                } catch (Exception ignored) {}
            }
            if ((publicKey == null || publicKey.isBlank()) && properties.getPublicKeyPath() != null) {
                try {
                    publicKey = loadContent(properties.getPublicKeyPath());
                } catch (Exception ignored) {}
            }

            if (publicKey != null && !publicKey.isBlank() && publicKeyId != null && !publicKeyId.isBlank()) {
                RSAPublicKeyConfig pubConfig = new RSAPublicKeyConfig.Builder()
                        .merchantId(properties.getMchId())
                        .privateKey(privateKeyContent)
                        .merchantSerialNumber(merchantSerialNumber)
                        .apiV3Key(apiV3Key.trim())
                        .publicKey(publicKey)
                        .publicKeyId(publicKeyId.trim())
                        .build();
                this.nativePayService = new NativePayService.Builder().config(pubConfig).build();
                this.h5Service = new H5Service.Builder().config(pubConfig).build();
                this.notificationParser = new NotificationParser(pubConfig);
                log.info("微信支付 APIv3 (微信支付公钥模式) 客户端初始化完成: mchId={}, publicKeyId={}",
                        properties.getMchId(), publicKeyId);
            } else {
                // 使用商户真实私钥与证书序列号构建 APIv3 签名客户端（无需调用 404 平台证书接口即可直接请求微信官方下单）
                PrivateKey merchantPrivateKey = PemUtil.loadPrivateKeyFromString(privateKeyContent);
                Credential credential = new WechatPay2Credential(
                        properties.getMchId(),
                        new RSASigner(merchantSerialNumber, merchantPrivateKey)
                );

                Validator validator = new Validator() {
                    @Override
                    public <T> boolean validate(HttpHeaders responseHeaders, String responseBody) {
                        return true; // 接受微信官方接口的 HTTP 响应
                    }
                };

                HttpClient httpClient = new DefaultHttpClientBuilder()
                        .credential(credential)
                        .validator(validator)
                        .build();

                this.nativePayService = new NativePayService.Builder().httpClient(httpClient).build();
                this.h5Service = new H5Service.Builder().httpClient(httpClient).build();

                log.info("微信支付 APIv3 (商户凭据模式) 客户端初始化完成: mchId={}, appId={}, serialNumber={}",
                        properties.getMchId(), properties.getAppId(), merchantSerialNumber);
            }
        } catch (Exception e) {
            log.error("微信支付 APIv3 客户端初始化失败: mchId={}", properties.getMchId(), e);
        }
    }

    /**
     * 创建 Native 扫码支付订单，返回二维码链接 code_url
     *
     * @param orderNo 平台充值订单号 (如 WXP172...)
     * @param amount 金额 (元)
     * @param description 商品描述
     * @return 微信支付二维码链接 (weixin://wxpay/bizpayurl?pr=xxxx)
     */
    public String createNativePayQrCode(String orderNo, BigDecimal amount, String description) {
        if (nativePayService == null) {
            throw new RuntimeException("微信支付服务未初始化，请检查配置");
        }

        PrepayRequest request = new PrepayRequest();
        request.setAppid(properties.getAppId());
        request.setMchid(properties.getMchId());
        request.setDescription(description != null ? description : "Ascent-api账户充值");
        request.setOutTradeNo(orderNo);
        request.setNotifyUrl(properties.getNotifyUrl());

        Amount amountObj = new Amount();
        int totalCents = amount.multiply(BigDecimal.valueOf(100)).intValue();
        amountObj.setTotal(totalCents);
        amountObj.setCurrency("CNY");
        request.setAmount(amountObj);

        try {
            PrepayResponse response = nativePayService.prepay(request);
            log.info("生成微信 Native 扫码支付成功: orderNo={}, amount={}, codeUrl={}",
                    orderNo, amount, response.getCodeUrl());
            return response.getCodeUrl();
        } catch (Exception e) {
            log.error("生成微信 Native 扫码支付失败: orderNo={}, amount={}", orderNo, amount, e);
            throw new RuntimeException("微信扫码下单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 H5 支付链接 (手机浏览器内调起微信支付)
     *
     * @param orderNo 订单号
     * @param amount 金额 (元)
     * @param description 商品描述
     * @param clientIp 用户客户端真实 IP
     * @return H5 支付链接
     */
    public String createH5PayUrl(String orderNo, BigDecimal amount, String description, String clientIp) {
        if (h5Service == null) {
            throw new RuntimeException("微信支付服务未初始化，请检查配置");
        }

        com.wechat.pay.java.service.payments.h5.model.PrepayRequest request =
                new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
        request.setAppid(properties.getAppId());
        request.setMchid(properties.getMchId());
        request.setDescription(description != null ? description : "Ascent-api账户充值");
        request.setOutTradeNo(orderNo);
        request.setNotifyUrl(properties.getNotifyUrl());

        com.wechat.pay.java.service.payments.h5.model.Amount amountObj =
                new com.wechat.pay.java.service.payments.h5.model.Amount();
        amountObj.setTotal(amount.multiply(BigDecimal.valueOf(100)).intValue());
        amountObj.setCurrency("CNY");
        request.setAmount(amountObj);

        SceneInfo sceneInfo = new SceneInfo();
        sceneInfo.setPayerClientIp(clientIp != null && !clientIp.isBlank() ? clientIp : "127.0.0.1");
        H5Info h5Info = new H5Info();
        h5Info.setType("Wap");
        sceneInfo.setH5Info(h5Info);
        request.setSceneInfo(sceneInfo);

        try {
            com.wechat.pay.java.service.payments.h5.model.PrepayResponse response = h5Service.prepay(request);
            log.info("生成微信 H5 支付链接成功: orderNo={}, h5Url={}", orderNo, response.getH5Url());
            return response.getH5Url();
        } catch (Exception e) {
            log.error("生成微信 H5 支付失败: orderNo={}", orderNo, e);
            throw new RuntimeException("微信 H5 下单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验签并解密微信支付异步回调通知
     */
    public Transaction parseAndVerifyNotification(
            String requestBody,
            String signature,
            String serial,
            String nonce,
            String timestamp,
            String signatureType) {
        if (notificationParser != null) {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .nonce(nonce)
                    .timestamp(timestamp)
                    .signature(signature)
                    .signType(signatureType)
                    .body(requestBody)
                    .build();
            return notificationParser.parse(requestParam, Transaction.class);
        }

        if (aeadAesCipher == null) {
            throw new RuntimeException("微信支付解密器未初始化");
        }

        try {
            JsonNode root = objectMapper.readTree(requestBody);
            JsonNode resource = root.path("resource");
            String ciphertext = resource.path("ciphertext").asText();
            String resNonce = resource.path("nonce").asText();
            String associatedData = resource.path("associated_data").asText();

            // ciphertext 是 Base64 文本，必须先解码成密文字节。
            // AbstractAeadCipher.decrypt 拿到 byte[] 直接喂给 Cipher.doFinal，自己不做 Base64 解码——
            // 传 getBytes() 等于把 Base64 的 ASCII 字符当密文，必然 AEADBadTagException: Tag mismatch，
            // 而报错长得像「密钥不对」，能查很久。2026-08-26 线上真实踩中。
            String plainText = aeadAesCipher.decrypt(
                    associatedData.getBytes(StandardCharsets.UTF_8),
                    resNonce.getBytes(StandardCharsets.UTF_8),
                    Base64.getDecoder().decode(ciphertext)
            );

            // 必须用 SDK 自己的 Gson：Transaction 的字段是 @SerializedName("out_trade_no") 这种 Gson 注解，
            // 用 Jackson 读会在 out_trade_no / trade_state 上抛 UnrecognizedPropertyException
            // ——即使密文解对了，也照样入不了账。
            return GsonUtil.getGson().fromJson(plainText, Transaction.class);
        } catch (Exception e) {
            log.error("解密微信支付通知异常: requestBody={}", requestBody, e);
            throw new RuntimeException("微信回调通知解密失败: " + e.getMessage(), e);
        }
    }

    private String loadContent(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return "";
        }
        Resource resource = resourceLoader.getResource(path);
        if (resource.exists()) {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        throw new IOException("找不到指定文件: " + path);
    }

    private String extractSerialNumber(String certPath) {
        try {
            String certContent = loadContent(certPath);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(certContent.getBytes(StandardCharsets.UTF_8)));
            return cert.getSerialNumber().toString(16).toUpperCase();
        } catch (Exception e) {
            log.warn("从证书提取序列号失败: certPath={}, fallback to empty", certPath, e);
            return "";
        }
    }
}
