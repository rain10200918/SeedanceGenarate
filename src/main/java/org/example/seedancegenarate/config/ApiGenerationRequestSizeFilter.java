package org.example.seedancegenarate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.example.seedancegenarate.exception.ApiErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** 在 JSON 反序列化前限制外部生成请求，包括没有 Content-Length 的分块传输。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiGenerationRequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final PathPattern PATH = PathPatternParser.defaultInstance.parse("/api/v1/videos");
    private final ObjectMapper objectMapper;

    public ApiGenerationRequestSizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PATH.matches(ServletRequestPathUtils.parseAndCache(request).pathWithinApplication());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            if (response.isCommitted()) return;
            response.setStatus(413);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                    new ApiErrorResponse.ApiError("PAYLOAD_TOO_LARGE", "请求体不能超过 64KiB",
                            org.example.seedancegenarate.exception.ApiExceptionHandler.requestId(request))));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        if (listener == null) throw new IllegalArgumentException("readListener 不能为空");
                        try {
                            if (!isFinished()) listener.onDataAvailable();
                            if (isFinished()) listener.onAllDataRead();
                        } catch (IOException e) { listener.onError(e); }
                    }
                };
            }
        }, response);
    }
}
