package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.ApiKey;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiRequestFingerprintTest {
    private ApiVideoService.CreateContext request(String prompt, String model, List<String> images,
            List<String> videos, List<String> audios, Integer duration, String ratio, Double megapixels) {
        return new ApiVideoService.CreateContext(new ApiKey(), "key", "ip", "ua", prompt, model,
                images, videos, audios, duration, ratio, megapixels);
    }
    private String hash(ApiVideoService.CreateContext value) { return ApiRequestFingerprint.of(value); }

    // 【测什么】非法UTF-16代理字符在编码前拒绝，合法emoji及问号可正常计算。
    // 【怎么算红】使用默认UTF-8替换编码时，孤立代理字符不会抛错，测试失败。
    @Test void rejectsMalformedUnicodeWithoutReplacingIt() {
        for (String malformed : List.of("p\uD800", "p\uDC00", "\uD800x")) {
            var failure = assertThrows(org.example.seedancegenarate.exception.ApiException.class,
                    () -> hash(request(malformed, "m", null, null, null, null, null, null)));
            assertEquals(400, failure.getHttpStatus().value());
        }
        assertNotEquals(hash(request("p?", "m", null, null, null, null, null, null)),
                hash(request("p\uD83D\uDE00", "m", null, null, null, null, null, null)));
    }

    // 【测什么】空列表与省略等价、边缘空格不影响原始参数身份，hash稳定且不存明文。
    // 【怎么算红】去掉trim或把null列表编码成不同值，等价断言失败。
    @Test void syntaxNormalizationHasStableVersionedIdentity() {
        var first = request(" p ", " model ", List.of(" https://example.test/a "), null, null, 5, "16:9", 1.0);
        var second = request("p", "model", List.of("https://example.test/a"), List.of(), List.of(), 5, "16:9", 1.0);
        assertEquals(hash(first), hash(second));
        assertTrue(hash(first).matches("[a-f0-9]{64}"));
    }

    // 【测什么】八个业务字段都参与指纹，包括媒体类型/顺序与显式默认值，分隔符无歧义。
    // 【怎么算红】漏任一字段或无长度帧拼接字符串，相应不等断言失败。
    @Test void everyParameterAndMediaOrderMatters() {
        String base = hash(request("p", "m", List.of("a", "b"), List.of("v"), List.of("s"), null, null, null));
        var variations = List.of(
                request("p2", "m", List.of("a", "b"), List.of("v"), List.of("s"), null, null, null),
                request("p", "m2", List.of("a", "b"), List.of("v"), List.of("s"), null, null, null),
                request("p", "m", List.of("b", "a"), List.of("v"), List.of("s"), null, null, null),
                request("p", "m", List.of("a", "b"), List.of("v2"), List.of("s"), null, null, null),
                request("p", "m", List.of("a", "b"), List.of("v"), List.of("s2"), null, null, null),
                request("p", "m", List.of("a", "b"), List.of("v"), List.of("s"), 8, null, null),
                request("p", "m", List.of("a", "b"), List.of("v"), List.of("s"), null, "16:9", null),
                request("p", "m", List.of("a", "b"), List.of("v"), List.of("s"), null, null, 1.0));
        for (var value : variations) assertNotEquals(base, hash(value));
        assertNotEquals(hash(request("a|b", "c", null, null, null, null, null, null)),
                hash(request("a", "b|c", null, null, null, null, null, null)));
        assertNotEquals(hash(request("p", "m", List.of("a"), List.of("b"), null, null, null, null)),
                hash(request("p", "m", List.of("a", "b"), null, null, null, null, null)));
    }
}
