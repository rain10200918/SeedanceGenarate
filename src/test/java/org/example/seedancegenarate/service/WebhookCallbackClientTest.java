package org.example.seedancegenarate.service;

import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.util.EntityUtils;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebhookCallbackClientTest {
    private static InetAddress ip(String value) throws Exception {
        // All callers supply numeric literals; never resolve a hostname in a test.
        return InetAddress.getByName(value);
    }

    // 【测什么】保存/发送共用校验拒绝全部非公网范围，包括IPv6/映射/文档/CGNAT。
    // 【怎么算红】删掉对应地址范围守卫，对应参数将不再抛400。
    @ParameterizedTest @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "10.1.2.3",
            "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "224.1.1.1",
            "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1",
            "64:ff9b::7f00:1", "2002:7f00:1::", "2001:db8::1", "2001::1", "3fff::1"})
    void blocksNonPublicAddresses(String address) throws Exception {
        var client = new WebhookCallbackClient(host -> new InetAddress[]{ipUnchecked(address)});
        assertEquals(400, assertThrows(BusinessException.class,
                () -> client.validate("https://callback.example/path?token=hidden")).getCode());
    }

    private static InetAddress ipUnchecked(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }

    // 【测什么】普通公网IPv4/IPv6均可保存，端口65535合法；不能过宽屏蔽192.0整个段。
    // 【怎么算红】把192.0/16全拒或禁全部IPv6，合法目标不再返回。
    @ParameterizedTest @ValueSource(strings = {"8.8.8.8", "192.0.8.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
    void allowsPublicAddresses(String address) {
        var client = new WebhookCallbackClient(host -> new InetAddress[]{ipUnchecked(address)});
        assertEquals("https://callback.example:65535/hook", client.validate("https://callback.example:65535/hook").url());
    }

    // 【测什么】非法URL在DNS之前失败，不泄漏URL或query凭证。
    // 【怎么算红】放开HTTP/userinfo/fragment/端口/控制字符任一守卫，异常或零DNS断言变红。
    @ParameterizedTest @ValueSource(strings = {"http://callback.example", "file:///tmp/a",
            "https://user:secret@callback.example", "https://callback.example/#token=secret",
            "https://callback.example:0", "https://callback.example:65536", "https://callback.example:",
            "https://callback.example:-1", "https://callback.example/a\nb", "https://[fe80::1%25eth0]/"})
    void rejectsMalformedBeforeDns(String url) throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        var e = assertThrows(BusinessException.class, () -> new WebhookCallbackClient(dns).validate(url));
        assertFalse(e.getMessage().contains("secret"));
        assertNull(e.getCause());
        verifyNoInteractions(dns);
    }

    // 【测什么】长度上限、空配置、混合DNS、空DNS和DNS异常均按明确边界处理。
    // 【怎么算红】移除长度/全部IP/空DNS检查，或回显异常，断言会失败。
    @Test void emptyLengthAndDnsFailures() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        var client = new WebhookCallbackClient(dns);
        assertNull(client.validate(null)); assertNull(client.validate(" \t "));
        when(dns.resolve(anyString())).thenReturn(new InetAddress[]{ip("8.8.8.8")});
        String base = "https://callback.example/";
        assertEquals(512, client.validate(base + "a".repeat(512 - base.length())).url().length());
        assertThrows(BusinessException.class, () -> client.validate(base + "a".repeat(513 - base.length())));
        when(dns.resolve(anyString())).thenReturn(new InetAddress[]{ip("8.8.8.8"), ip("10.0.0.1")});
        assertThrows(BusinessException.class, () -> client.validate(base));
        when(dns.resolve(anyString())).thenReturn(new InetAddress[0]);
        assertEquals(503, assertThrows(BusinessException.class, () -> client.validate(base)).getCode());
        when(dns.resolve(anyString())).thenThrow(new UnknownHostException("token=hidden"));
        var failure = assertThrows(BusinessException.class, () -> client.validate(base));
        assertEquals(503, failure.getCode()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("hidden"));
    }

    // 【测什么】实际POST保留域名/payload/signature，302原样返回且关闭资源。
    // 【怎么算红】更改URL为IP、漏签名/正文、忽略关闭或把302当成功，断言失败。
    @Test void postPreservesAuthorityBodyAndClosesResponse() throws Exception {
        var client = spy(new WebhookCallbackClient(host -> new InetAddress[]{ipUnchecked("8.8.8.8")}));
        var target = client.validate("https://callback.example/path?token=hidden");
        CloseableHttpClient http = mock(CloseableHttpClient.class);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        doReturn(http).when(client).clientFor(target);
        when(http.execute(any(org.apache.http.client.methods.HttpUriRequest.class))).thenReturn(response);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 302, "Found"));
        assertEquals(302, client.post(target, "{\"task_id\":\"t1\"}", "signed"));
        var request = ArgumentCaptor.forClass(org.apache.http.client.methods.HttpUriRequest.class);
        verify(http).execute(request.capture());
        assertEquals("callback.example", request.getValue().getURI().getHost());
        assertEquals("signed", request.getValue().getFirstHeader("X-Signature").getValue());
        assertEquals("{\"task_id\":\"t1\"}", EntityUtils.toString(((HttpPost)request.getValue()).getEntity()));
        verify(response).close(); verify(http).close();
    }

    // 【测什么】连接解析器仅使用本轮IP，拒不同host；禁redirect/隐式retry，TLS hostname verifier严格。
    // 【怎么算红】改用系统DNS、去掉禁跳转/重试或替换宽松TLS verifier，相应断言失败。
    @Test void pinsDnsAndRetainsStrictTlsAndNoRedirect() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("callback.example")).thenReturn(new InetAddress[]{ip("8.8.8.8")});
        var client = new WebhookCallbackClient(dns);
        var target = client.validate("https://callback.example/path");
        when(dns.resolve(anyString())).thenReturn(new InetAddress[]{ip("127.0.0.1")});
        var builder = mock(HttpClientBuilder.class, RETURNS_SELF);
        try (var factories = mockStatic(HttpClients.class)) {
            factories.when(HttpClients::custom).thenReturn(builder);
            client.clientFor(target);
            ArgumentCaptor<DnsResolver> pinned = ArgumentCaptor.forClass(DnsResolver.class);
            verify(builder).setDnsResolver(pinned.capture());
            assertArrayEquals(new InetAddress[]{ip("8.8.8.8")}, pinned.getValue().resolve("callback.example"));
            assertThrows(UnknownHostException.class, () -> pinned.getValue().resolve("other.example"));
            verify(dns, times(1)).resolve(anyString());
            verify(builder).disableRedirectHandling(); verify(builder).disableAutomaticRetries();
            var tls = ArgumentCaptor.forClass(org.apache.http.conn.socket.LayeredConnectionSocketFactory.class);
            verify(builder).setSSLSocketFactory(tls.capture());
            assertInstanceOf(SSLConnectionSocketFactory.class, tls.getValue());
            var verifier = (DefaultHostnameVerifier) ReflectionTestUtils.getField(tls.getValue(), "hostnameVerifier");
            X509Certificate cert = mock(X509Certificate.class);
            when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2, "other.example")));
            assertThrows(SSLException.class, () -> verifier.verify("callback.example", cert));
        }
    }
}
