package org.example.seedancegenarate.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动配置指纹的密钥掩码守卫。
 * <p>
 * 第一版判定写的是 {@code contains("api-key")}，结果 {@code wechat.pay.api-v3-key}
 * 因为中间夹了 {@code v3} 没匹配上，<b>把商户 APIv3 密钥明文打进了日志</b>。
 * 而这条日志本身就是为了「不再让密钥进日志」才加的。
 */
class StartupConfigLoggerTest {

    private final StartupConfigLogger logger = new StartupConfigLogger(new MockEnvironment());

    @Test
    void keysWithInfixesAreStillTreatedAsSecrets() {
        // 【测什么】名字中间夹了东西的密钥也认得出来
        // 【怎么算红】回到 contains 匹配 —— wechat.pay.api-v3-key 明文进日志，
        //            而这条日志会被 docker logs 长期保留、被日志采集器转走
        assertTrue(logger.isSecret("wechat.pay.api-v3-key"), "api-v3-key 必须当密钥");
        assertTrue(logger.isSecret("video.completion.callback-secret"));
        assertTrue(logger.isSecret("video.comfyui.access-token"));
        assertTrue(logger.isSecret("spring.datasource.password"));
        assertTrue(logger.isSecret("alipay.app-private-key"));
    }

    @Test
    void configNamesThatMerelyContainKeyAreNotMasked() {
        // 【测什么】「名字里有 key 但不是密钥」的配置不能被误伤
        // 【怎么算红】按 contains("key") 判 —— key-prefix 变成一串指纹，
        //            而排查跨环境串台时最需要看的就是这个前缀的**原值**
        assertFalse(logger.isSecret("distributed.lock.key-prefix"), "key-prefix 是前缀不是密钥");
        assertFalse(logger.isSecret("rate-limit.redis-key-prefix"));
        assertFalse(logger.isSecret("wechat.pay.mch-id"));
        assertFalse(logger.isSecret("task-events.channel"));
    }

    @Test
    void fingerprintNeverContainsTheSecretItself() {
        // 【测什么】密钥的输出里不含原文，只有长度和摘要前缀
        // 【怎么算红】直接打值或打前几位 —— 就是今天在提交日志里踩的那个坑的翻版
        MockEnvironment env = new MockEnvironment();
        String secret = "9f3a1c8e2b7d4f6a8c0e1d2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
        env.setProperty("video.completion.callback-secret", secret);

        StringBuilder captured = new StringBuilder();
        new StartupConfigLogger(env) {
            @Override
            public void logEffectiveConfig() {
                captured.append(render("video.completion.callback-secret"));
            }
        }.logEffectiveConfig();

        assertFalse(captured.toString().contains(secret), "输出里出现了原文：" + captured);
        assertFalse(captured.toString().contains(secret.substring(0, 8)), "连前 8 位都不该出现");
        assertTrue(captured.toString().contains("len=" + secret.length()), "实际=" + captured);
    }
}
