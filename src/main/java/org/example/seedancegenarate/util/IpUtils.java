package org.example.seedancegenarate.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public class IpUtils {
    private static final String UNKNOWN = "unknown";

    /** 离线 IP 库（resources/ip2region.xdb，懒加载一次进内存）；加载失败为 null（空数组标记） */
    private static volatile byte[] XDB_BUFFER;

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_CLIENT_IP",
                "HTTP_X_FORWARDED_FOR"
        };
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !UNKNOWN.equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    public static String getIpLocation(HttpServletRequest request, String ip) {
        // 1. 代理 / CDN 注入的地理头优先（可覆盖离线库精度）
        String headerLocation = request == null ? null : firstText(
                request.getHeader("X-IP-Location"),
                request.getHeader("X-Ip-Location"),
                request.getHeader("X-Geo-Location"),
                request.getHeader("CF-IPCountry")
        );
        if (StringUtils.hasText(headerLocation)) {
            return headerLocation;
        }
        if (!StringUtils.hasText(ip)) {
            return "未知";
        }
        // 2. 本机 / 内网
        if (isLocalIp(ip)) {
            return "本机/内网";
        }
        // 3. 离线库（ip2region，无外部依赖）
        String region = searchIp2Region(ip);
        if (region != null) {
            return region;
        }
        return "未知";
    }

    /** 离线查询；数据缺失 / 查询失败返回 null（调用方回退「未知」，不抛异常） */
    private static String searchIp2Region(String ip) {
        try {
            byte[] buffer = xdbBuffer();
            if (buffer == null) {
                return null;
            }
            String region = Searcher.newWithBuffer(buffer).search(ip);
            return formatRegion(region);
        } catch (Exception e) {
            log.debug("IP 属地解析失败: {} ({})", ip, e.getMessage());
            return null;
        }
    }

    /** 懒加载 xdb 进内存（约 11MB，全量加载查询最快）；缺失时标记空数组避免反复尝试 */
    private static byte[] xdbBuffer() {
        byte[] buffer = XDB_BUFFER;
        if (buffer == null) {
            synchronized (IpUtils.class) {
                buffer = XDB_BUFFER;
                if (buffer == null) {
                    try (InputStream in = new ClassPathResource("ip2region.xdb").getInputStream()) {
                        buffer = in.readAllBytes();
                    } catch (Exception e) {
                        log.warn("IP 属地库缺失（resources/ip2region.xdb），属地将显示未知: {}", e.getMessage());
                        buffer = new byte[0];
                    }
                    XDB_BUFFER = buffer;
                }
            }
        }
        return buffer.length == 0 ? null : buffer;
    }

    /** "中国|广东省|深圳市|电信" → "广东省 深圳市"；国外取国家；异常段（0/内网IP）过滤 */
    private static String formatRegion(String region) {
        if (!StringUtils.hasText(region)) {
            return null;
        }
        String[] parts = region.split("\\|");
        String province = parts.length > 1 ? parts[1] : "0";
        String city = parts.length > 2 ? parts[2] : "0";
        if (!"0".equals(province)) {
            return "0".equals(city) || city.equals(province) ? province : province + " " + city;
        }
        String country = parts[0];
        if (!"0".equals(country) && !"内网IP".equals(country) && !"本机地址".equals(country)) {
            return country;
        }
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value) && !UNKNOWN.equalsIgnoreCase(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public static boolean isPrivateOrLocalAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || "169.254.169.254".equals(address.getHostAddress());
    }

    public static boolean isLocalIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return isPrivateOrLocalAddress(address);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
