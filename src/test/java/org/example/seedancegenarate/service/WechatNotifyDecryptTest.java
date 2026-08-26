package org.example.seedancegenarate.service;

import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.service.payments.model.Transaction;
import org.example.seedancegenarate.config.WechatPayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 微信回调解密守卫。
 * <p>
 * 2026-08-26 线上：回调确实到了，却每次 {@code AEADBadTagException: Tag mismatch}，
 * 报错长得像「APIv3 密钥配错了」，实际是把 Base64 文本的 ASCII 字节当成密文喂进了 AES-GCM。
 * 用真实通知验过：Base64 解码后同一把密钥解得开，密钥本来就是对的。
 */
class WechatNotifyDecryptTest {

    /** 测试用 APIv3 密钥，必须正好 32 字符（AES-256） */
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NONCE = "abcdefghijkl";        // 微信固定 12 字符
    private static final String AAD = "transaction";

    private WechatPaymentService serviceWithCipher() {
        WechatPayProperties props = new WechatPayProperties();
        props.setEnabled(false); // 不跑 @PostConstruct 的初始化，只测解密这一段
        WechatPaymentService service = new WechatPaymentService(props, new DefaultResourceLoader());
        ReflectionTestUtils.setField(service, "aeadAesCipher",
                new AeadAesCipher(API_V3_KEY.getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(service, "notificationParser", null);
        return service;
    }

    /** 按微信 APIv3 的规则造一条通知体：resource.ciphertext 是 Base64 文本 */
    private String notifyBody(String plainJson) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder()
                .encodeToString(cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8)));
        return "{\"id\":\"evt-1\",\"event_type\":\"TRANSACTION.SUCCESS\",\"resource\":{"
                + "\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"ciphertext\":\"" + ciphertext + "\","
                + "\"associated_data\":\"" + AAD + "\","
                + "\"nonce\":\"" + NONCE + "\"}}";
    }

    @Test
    void base64CiphertextIsDecodedBeforeDecryption() throws Exception {
        // 测什么：resource.ciphertext 是 Base64 文本，必须先解码成字节再进 AES-GCM
        // 怎么算红：直接 ciphertext.getBytes() —— AbstractAeadCipher 拿 byte[] 就 doFinal，
        //          自己不解 Base64，于是把 Base64 的 ASCII 当密文，必然 Tag mismatch。
        //          用户付了钱、回调也到了，却永远入不了账；而报错像「密钥配错」，会往完全错的方向查。
        //          2026-08-26 线上真实踩中（订单 WXP1787713116808551315，0.01 元）
        String body = notifyBody("{\"mchid\":\"1116376414\",\"out_trade_no\":\"WXP123\","
                + "\"transaction_id\":\"4200TX\",\"trade_state\":\"SUCCESS\","
                + "\"amount\":{\"total\":1,\"currency\":\"CNY\"}}");

        Transaction tx = serviceWithCipher().parseAndVerifyNotification(
                body, "sig", "serial", NONCE, "1787713138", "WECHATPAY2-SHA256-RSA2048");

        assertEquals("WXP123", tx.getOutTradeNo());
        assertEquals("4200TX", tx.getTransactionId());
        assertEquals(Transaction.TradeStateEnum.SUCCESS, tx.getTradeState());
        assertEquals(1, tx.getAmount().getTotal());
    }

    @Test
    void wrongKeyStillFails() {
        // 测什么：Base64 解码不能把「密钥真的错了」也一起放过去
        // 怎么算红：改完之后什么密文都能过 —— 那就等于没有认证，伪造回调可以直接给自己充值
        WechatPayProperties props = new WechatPayProperties();
        props.setEnabled(false);
        WechatPaymentService service = new WechatPaymentService(props, new DefaultResourceLoader());
        ReflectionTestUtils.setField(service, "aeadAesCipher",
                new AeadAesCipher("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(service, "notificationParser", null);

        assertThrows(RuntimeException.class, () -> service.parseAndVerifyNotification(
                notifyBody("{\"out_trade_no\":\"WXP123\"}"),
                "sig", "serial", NONCE, "1787713138", "WECHATPAY2-SHA256-RSA2048"));
    }
}
