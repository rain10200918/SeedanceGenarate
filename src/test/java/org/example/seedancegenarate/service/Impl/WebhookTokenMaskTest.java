package org.example.seedancegenarate.service.Impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回调地址进日志前必须抹掉 token。
 * <p>
 * 2026-08-26 线上实证：每次提交都打出
 * {@code webhookUrl=http://192.168.10.33:8080/api/callback/comfyui?token=9f3a1c8e...}，
 * 而这个串线上与 {@code COMFYUI_ACCESS_TOKEN} 是<b>同一个</b> ——
 * 拿到它就能穿过 nginx 直接用 GPU（提交工作流、上传文件、下载任何产物）。
 */
class WebhookTokenMaskTest {

    private static final String SECRET = "9f3a1c8e2b7d4f6a8c0e1d2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";

    private String mask(String url) {
        return (String) ReflectionTestUtils.invokeMethod(
                new VideoSubmitServiceImpl(null, null, null, null, null, null, null, null, null,
                        null, null, null, null),
                "maskToken", url);
    }

    @Test
    void tokenIsStrippedFromTheLoggedUrl() {
        // 【测什么】token 值被 *** 替掉，其余部分（主机、端口、路径）原样保留
        // 【怎么算红】原样输出 —— 生产密钥继续每次提交写一条进 docker logs，
        //            任何拿到日志的人都能直接用你的 GPU
        String masked = mask("http://192.168.10.33:8080/api/callback/comfyui?token=" + SECRET);

        assertFalse(masked.contains(SECRET), "密钥泄漏了：" + masked);
        assertEquals("http://192.168.10.33:8080/api/callback/comfyui?token=***", masked);
    }

    @Test
    void tokenIsStrippedWhenItIsNotTheFirstParam() {
        // 【测什么】token 不在第一个参数位、且后面还有参数时也要抹掉
        // 【怎么算红】只处理 "?token=" 开头的情况 —— 换个参数顺序密钥就照样打出去了
        String masked = mask("http://h/cb?a=1&token=" + SECRET + "&b=2");

        assertFalse(masked.contains(SECRET), "密钥泄漏了：" + masked);
        assertTrue(masked.contains("a=1") && masked.contains("b=2"), "别的参数不该被吃掉：" + masked);
    }

    @Test
    void missingWebhookStillReadsAsPollingMode() {
        // 【测什么】没配回调时保持原来的可读输出（运维靠这行判断"这台是不是事件驱动"）
        // 【怎么算红】打成 null/空 —— 排查回调有没有生效时看不出区别
        assertEquals("无（轮询推进）", mask(null));
        assertEquals("无（轮询推进）", mask(""));
    }
}
