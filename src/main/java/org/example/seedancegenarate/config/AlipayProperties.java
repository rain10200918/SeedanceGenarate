package org.example.seedancegenarate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付宝支付配置
 * 对应 application.yaml 中的 alipay 配置段
 */
@Data
@Component
@ConfigurationProperties(prefix = "alipay")
public class AlipayProperties {

    /** 应用 ID */
    private String appId;

    /** 应用私钥路径（相对或绝对路径） */
    private String appPrivateKeyPath;

    /** 支付宝公钥路径 */
    private String alipayPublicKeyPath;

    /** 网关地址（生产环境） */
    private String gatewayUrl = "https://openapi.alipay.com/gateway.do";

    /** 沙箱网关地址 */
    private String sandboxGatewayUrl = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    /** 是否使用沙箱环境 */
    private boolean sandbox = false;

    /** 签名算法（RSA2 推荐） */
    private String signType = "RSA2";

    /** 字符集 */
    private String charset = "UTF-8";

    /** 数据格式 */
    private String format = "json";

    /** 回调通知 URL（支付宝服务器回调） */
    private String notifyUrl;

    /** 前端回跳 URL（用户支付完成后浏览器跳转） */
    private String returnUrl;

    /**
     * 获取实际使用的网关地址
     */
    public String getActualGatewayUrl() {
        return sandbox ? sandboxGatewayUrl : gatewayUrl;
    }
}
