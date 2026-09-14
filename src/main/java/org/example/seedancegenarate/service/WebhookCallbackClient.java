package org.example.seedancegenarate.service;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Callback-only transport. The original hostname remains the TLS and HTTP authority. */
@Component
public class WebhookCallbackClient {
    private final DnsResolver dns;

    public WebhookCallbackClient() {
        this(SystemDefaultDnsResolver.INSTANCE);
    }

    WebhookCallbackClient(DnsResolver dns) {
        this.dns = dns;
    }

    public static final class ValidatedTarget {
        private final URI uri;
        private final InetAddress[] addresses;

        private ValidatedTarget(URI uri, InetAddress[] addresses) {
            this.uri = uri;
            this.addresses = addresses.clone();
        }

        public String url() { return uri.toASCIIString(); }
    }

    /** DNS must not inherit a caller's database transaction. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ValidatedTarget validate(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) return null;
        try {
            String value = callbackUrl.trim();
            if (value.length() > 512 || value.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid();
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) throw invalid();
            // Scoped IPv6 is never a public callback destination.
            if (host.contains("%")) throw invalid();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            InetAddress[] addresses = dns.resolve(host);
            if (addresses == null || addresses.length == 0)
                throw new BusinessException(503, "回调地址暂时无法解析，请稍后重试");
            for (InetAddress address : addresses) {
                if (!isPublic(address)) throw invalid();
            }
            if (uri.toASCIIString().length() > 512) throw invalid();
            return new ValidatedTarget(uri, addresses);
        } catch (UnknownHostException e) {
            throw new BusinessException(503, "回调地址暂时无法解析，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Do not attach URI/DNS exception text: it can contain query credentials.
            throw invalid();
        }
    }

    public int post(ValidatedTarget target, String payload, String signature) throws IOException {
        if (target == null) throw invalid();
        HttpPost request = new HttpPost(target.uri);
        request.setHeader("X-Signature", signature);
        request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        try (CloseableHttpClient client = clientFor(target);
             var response = client.execute(request)) {
            return response.getStatusLine().getStatusCode();
        }
    }

    CloseableHttpClient clientFor(ValidatedTarget target) {
        String expected = target.uri.getHost().replace("[", "").replace("]", "");
        return HttpClients.custom()
                .setDnsResolver(host -> {
                    if (!expected.equalsIgnoreCase(host.replace("[", "").replace("]", "")))
                        throw new UnknownHostException("Callback host mismatch");
                    return target.addresses.clone();
                })
                .setSSLSocketFactory(SSLConnectionSocketFactory.getSocketFactory())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(10_000).setSocketTimeout(10_000)
                        .setConnectionRequestTimeout(10_000).build())
                .build();
    }

    private static BusinessException invalid() {
        return BusinessException.badRequest("回调地址必须是可解析的公网 HTTPS 地址");
    }

    private static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, second = b[1] & 255;
        if (b.length == 4) {
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && second >= 64 && second <= 127)
                    && !(a == 169 && second == 254)
                    && !(a == 172 && second >= 16 && second <= 31)
                    && !(a == 192 && ((second == 0 && ((b[2] & 255) == 0 || (b[2] & 255) == 2)) || second == 168
                        || (second == 88 && (b[2] & 255) == 99)))
                    && !(a == 198 && (second == 18 || second == 19
                        || (second == 51 && (b[2] & 255) == 100)))
                    && !(a == 203 && second == 0 && (b[2] & 255) == 113);
        }
        // Only global unicast; also exclude transition/special/documentation ranges.
        if (b.length != 16 || (a & 0xe0) != 0x20) return false;
        if (a == 0x20 && second == 0x02) return false; // 6to4 embeds IPv4
        if (a == 0x20 && second == 0x01) {
            int third = b[2] & 255, fourth = b[3] & 255;
            if (third < 2 || (third == 0x0d && fourth == 0xb8)) return false;
        }
        return !(a == 0x3f && second == 0xff && (b[2] & 255) < 0x10);
    }
}
