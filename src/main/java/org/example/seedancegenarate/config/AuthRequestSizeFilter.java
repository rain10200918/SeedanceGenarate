package org.example.seedancegenarate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.example.seedancegenarate.entity.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 在 JSON 反序列化与验证码限流前，限制公开认证入口的请求体资源消耗。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthRequestSizeFilter extends OncePerRequestFilter {
    private static final List<PathPattern> GUARDED_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/api/captcha/get"),
            PathPatternParser.defaultInstance.parse("/api/captcha/check"),
            PathPatternParser.defaultInstance.parse("/api/auth/login"),
            PathPatternParser.defaultInstance.parse("/api/auth/register"),
            PathPatternParser.defaultInstance.parse("/api/auth/register/email-code"),
            PathPatternParser.defaultInstance.parse("/api/auth/register/email-code/resend")
    );

    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;

    public AuthRequestSizeFilter(
            ObjectMapper objectMapper,
            @Value("${auth.request-body-max-bytes:16384}") int maxBodyBytes
    ) {
        if (maxBodyBytes <= 0 || maxBodyBytes > 1_048_576) {
            throw new IllegalStateException("auth.request-body-max-bytes 必须在 1 到 1048576 之间");
        }
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        return GUARDED_PATHS.stream().noneMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            rejectOversized(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void rejectOversized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                Result.fail(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "请求体过大")
        );
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                throw new IllegalArgumentException("readListener 不能为空");
            }
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException e) {
                readListener.onError(e);
            }
        }
    }
}
