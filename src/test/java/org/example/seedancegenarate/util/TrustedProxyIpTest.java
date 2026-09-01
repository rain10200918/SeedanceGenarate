package org.example.seedancegenarate.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedProxyIpTest {

    @Test
    void directRequestCannotSpoofItsRateLimitIpWithForwardingHeaders() {
        // 【测什么】应用只读取容器按受信代理链解析后的 remoteAddr，不直接采信客户端转发头。
        // 【怎么算红】让 IpUtils 再次优先读取 X-Forwarded-For，这条必须变红。
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.8");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.99");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.100");

        assertEquals("203.0.113.8", IpUtils.getClientIp(request));
    }
}
