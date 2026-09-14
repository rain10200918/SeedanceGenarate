package org.example.seedancegenarate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ApiGenerationRequestSizeFilterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApiGenerationRequestSizeFilter filter = new ApiGenerationRequestSizeFilter(json);

    // 【测什么】64KiB恰好通过且下游能完整重读原体，超过一字节返回API格式413。
    // 【怎么算红】改成>=上限会拒绝边界，移除限制会让超限请求到达下游。
    @Test void exactBoundaryAndOversize() throws Exception {
        for (int size : new int[]{65536, 65537}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/videos");
            request.setContent(new byte[size]);
            var response = new MockHttpServletResponse();
            var reached = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> {
                reached.set(true);
                assertEquals(size, req.getInputStream().readAllBytes().length);
            });
            assertEquals(size == 65536, reached.get());
            if (size > 65536) {
                assertEquals(413, response.getStatus());
                assertEquals("PAYLOAD_TOO_LARGE", json.readTree(response.getContentAsString()).at("/error/code").asText());
            }
        }
    }

    // 【测什么】缺失Content-Length也按实际流计数，应用context及路径参数不绕过限制。
    // 【怎么算红】仅检查Content-Length或原始URI相等会放行该超限请求。
    @Test void chunkedContextPathIsBounded() throws Exception {
        var request = new MockHttpServletRequest("POST", "/app/api/v1/videos;v=1") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContextPath("/app");
        request.setContent(new byte[65537]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("oversized request reached controller"));
        assertEquals(413, response.getStatus());
    }

    // 【测什么】只限制创建生成请求，不改变已有其他接口或GET路径行为。
    // 【怎么算红】过滤所有路径或忽略HTTP方法会使下游未被调用。
    @Test void unrelatedRoutesUntouched() throws Exception {
        for (String method : new String[]{"GET", "POST"}) {
            var request = new MockHttpServletRequest(method, "GET".equals(method) ? "/api/v1/videos" : "/api/other");
            request.setContent(new byte[65537]);
            var reached = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertTrue(reached.get());
        }
    }
}
