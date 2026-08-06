package org.example.seedancegenarate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 属地解析测试（依赖 resources/ip2region.xdb 离线库，纯本地无外部调用）。
 * 注意：具体省市区随数据版本可能变化，这里只验证「能解析出非未知结果」而非精确值。
 */
class IpUtilsTest {

    @Test
    void localIpShowsIntranet() {
        assertEquals("本机/内网", IpUtils.getIpLocation(null, "127.0.0.1"));
        assertEquals("本机/内网", IpUtils.getIpLocation(null, "0:0:0:0:0:0:0:1"));
        assertEquals("本机/内网", IpUtils.getIpLocation(null, "192.168.1.100"));
    }

    @Test
    void publicIpResolvesToLocation() {
        // 用户实际报障的公网 IP（116.78.54.185）；离线库应能解析出省/市，而不是「未知」
        String location = IpUtils.getIpLocation(null, "116.78.54.185");
        assertNotEquals("未知", location, "公网 IP 不应解析为未知");
        assertFalse(location.isBlank());
    }

    @Test
    void blankIpStaysUnknown() {
        assertEquals("未知", IpUtils.getIpLocation(null, null));
        assertEquals("未知", IpUtils.getIpLocation(null, ""));
    }

    @Test
    void unknownIpDoesNotThrow() {
        // 非法 IP 查询不应抛异常（回退未知）
        assertTrue(IpUtils.getIpLocation(null, "999.999.999.999").length() >= 0);
    }
}
