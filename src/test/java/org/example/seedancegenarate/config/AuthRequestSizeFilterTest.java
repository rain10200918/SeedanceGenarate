package org.example.seedancegenarate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthRequestSizeFilterTest {

    @Test
    void oversizedMatrixParameterAuthBodyIsRejectedBeforeTheFilterChain() throws Exception {
        // 【测什么】请求体守卫与 Spring MVC 使用相同 PathPattern 语义；matrix 参数不能绕开有界读取。
        // 【怎么算红】若按 raw requestURI 精确比较，login;foo=bar 会跳过 filter 并调用 FilterChain。
        AuthRequestSizeFilter filter = new AuthRequestSizeFilter(new ObjectMapper(), 16);
        MockHttpServletRequest request = request(
                "/api/auth/login;foo=bar",
                "x".repeat(17).getBytes(StandardCharsets.UTF_8)
        );
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertEquals(413, new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").intValue());
        assertEquals("请求体过大", new ObjectMapper().readTree(response.getContentAsByteArray()).path("message").textValue());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void normalCaptchaBodyIsReplayedWithoutTruncation() throws Exception {
        // 【测什么】有界预读后的正常 JSON 必须原样交给 Spring MVC，不能因 filter 消费流而变成空 body。
        // 【怎么算红】若 wrapper 没缓存/回放请求体，下游读到的字节将为空并使本测试变红。
        AuthRequestSizeFilter filter = new AuthRequestSizeFilter(new ObjectMapper(), 64);
        byte[] body = "{\"captchaType\":\"blockPuzzle\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request("/api/captcha/get", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (wrappedRequest, wrappedResponse) -> assertArrayEquals(
                body,
                ((ServletRequest) wrappedRequest).getInputStream().readAllBytes()
        );

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void unrelatedEndpointsAreNotBufferedByTheAuthGuard() throws Exception {
        // 【测什么】资源上限只影响六个公开认证 POST，不改变上传/生成等既有大 body 接口。
        // 【怎么算红】若 guarded path 判断扩大到全站，本请求会被 413 拒绝。
        AuthRequestSizeFilter filter = new AuthRequestSizeFilter(new ObjectMapper(), 8);
        MockHttpServletRequest request = request("/api/video/submit", new byte[32]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(same(request), same(response));
    }

    @Test
    void bothRegistrationEmailEndpointsAreGuardedBeforeJsonDeserialization() throws Exception {
        // 【测什么】发码与重发两个新公开 POST 都受相同的 16 KiB 前置守卫保护。
        // 【怎么算红】GUARDED_PATHS 漏掉任一路径时，该路径会进入 FilterChain 而不是返回 413。
        AuthRequestSizeFilter filter = new AuthRequestSizeFilter(new ObjectMapper(), 8);
        for (String path : new String[]{
                "/api/auth/register/email-code",
                "/api/auth/register/email-code/resend"
        }) {
            MockHttpServletRequest request = request(path, new byte[9]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(413, response.getStatus(), path);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    private static MockHttpServletRequest request(String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
