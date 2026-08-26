package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信支付 APIv3 配置
 * 对应 application.yaml 中的 wechat.pay 配置段
 */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.pay")
public class WechatPayProperties {

    /** 微信公众号 / 小程序 / 开放平台 AppID */
    private String appId = "wxbdfefbd6cca026e1";

    /** 微信支付商户号 MchId */
    private String mchId = "1116376414";

    /** APIv3 密钥（32 字符） */
    private String apiV3Key = "fe3c22dbd689b41a943423769e5a55e1";

    /** APIv3 密钥文件路径（可选，未直接提供 apiV3Key 时从此文件读取） */
    private String apiV3KeyPath = "classpath:wechat/apiv3_key.txt";

    /** 商户 API 私钥路径（classpath:wechat/apiclient_key.pem） */
    private String privateKeyPath = "classpath:wechat/apiclient_key.pem";

    /** 商户 API 证书路径（classpath:wechat/apiclient_cert.pem） */
    private String merchantCertPath = "classpath:wechat/apiclient_cert.pem";

    /** 商户证书序列号（可选，若未配置则自动从 merchantCertPath 读取） */
    private String merchantSerialNumber = "17578DB1B6BA6874062E062323BC5559C86A60C6";

    /** 微信支付公钥 ID (PUB_KEY_ID_xxx，微信支付公钥模式必填) */
    private String publicKeyId = "PUB_KEY_ID_0111163764142026081100292284003204";

    /** 微信支付公钥 ID 文件路径 */
    private String publicKeyIdPath = "classpath:wechat/public_key_id.txt";

    /** 微信支付公钥内容 (PEM 格式) */
    private String publicKey;

    /** 微信支付公钥文件路径 */
    private String publicKeyPath = "classpath:wechat/wechat_public_key.pem";

    /** 微信支付异步回调通知 URL */
    private String notifyUrl = "https://api-generate.creator.ascent-ai.cn/api/notify/wechat";

    /** 是否启用微信支付 */
    private boolean enabled = true;
}
