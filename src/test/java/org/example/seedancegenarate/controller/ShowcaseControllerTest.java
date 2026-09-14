package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.ApiExceptionHandler;
import org.example.seedancegenarate.service.ShowcaseService;
import org.example.seedancegenarate.service.ShowcaseStorage;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.URI;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class ShowcaseControllerTest {
    MockMvc mvc;
    ShowcaseStorage storage;
    ShowcaseService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V55__showcase_work.sql")).execute(ds);
        storage = mock(ShowcaseStorage.class);
        when(storage.sign(anyString())).thenReturn(URI.create("https://example.test/media?signature=example"));
        service = new ShowcaseService(new JdbcTemplate(ds), storage);
        mvc = MockMvcBuilders.standaloneSetup(new ShowcaseController(service), new AdminShowcaseController(service))
                .setControllerAdvice(new ApiExceptionHandler(), new GlobalExceptionHandler()).build();
        login("ADMIN");
    }
    @AfterEach void clear() { UserContext.clear(); }
    static void login(String role) {
        var user = new AppUser(); user.setId(1L); user.setRole(role); UserContext.setUser(user);
    }
    static MockMultipartFile png() {
        return new MockMultipartFile("file", "x.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10});
    }
    // 【测什么】真实全局advice链中仍返回HTTP401/403，不被RuntimeException处理器改成200。
    // 【怎么算红】移除局部异常处理器，状态断言会失败。
    @Test void accessHttpStatusWithRealAdvice() throws Exception {
        UserContext.clear();
        mvc.perform(get("/api/showcase")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/showcase/{id}/media", UUID.randomUUID())).andExpect(status().isUnauthorized());
        login("USER");
        mvc.perform(get("/api/admin/showcase")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/showcase").file(png()).param("title","T")
                .param("clientRequestId",UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/showcase/{id}",UUID.randomUUID()).contentType("application/json")
                .content("{\"expectedVersion\":0}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/showcase/{id}/media",UUID.randomUUID())).andExpect(status().isForbidden());
    }
    // 【测什么】HTTP上传、编辑发布、公开投影与签名、下架的完整闭环。
    // 【怎么算红】字段泄漏、漏状态过滤、漏302/no-store或CAS409均会失败。
    @Test void lifecycleHttp() throws Exception {
        var response = mvc.perform(multipart("/api/admin/showcase").file(png()).param("title","Title")
                .param("clientRequestId",UUID.randomUUID().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.mediaType").value("IMAGE")).andReturn();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(get("/api/showcase/{id}/media",id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/showcase/{id}/media",id)).andExpect(status().isFound()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(patch("/api/admin/showcase/{id}",id).contentType("application/json")
                .content("{\"expectedVersion\":0,\"status\":\"PUBLISHED\",\"sortOrder\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(patch("/api/admin/showcase/{id}",id).contentType("application/json")
                .content("{\"expectedVersion\":0,\"title\":\"stale\"}")).andExpect(status().isConflict());
        login("USER");
        mvc.perform(get("/api/showcase")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(id))
                .andExpect(jsonPath("$.data.records[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].mediaKey").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].requestHash").doesNotExist());
        mvc.perform(get("/api/showcase/{id}/media",id)).andExpect(status().isFound())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Location","https://example.test/media?signature=example"));
        mvc.perform(get("/api/showcase/{id}/media",id).param("cover","true")).andExpect(status().isNotFound());
        login("ADMIN");
        mvc.perform(patch("/api/admin/showcase/{id}",id).contentType("application/json")
                .content("{\"expectedVersion\":1,\"status\":\"OFFLINE\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/showcase/{id}/media",id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/showcase")).andExpect(jsonPath("$.data.total").value(0));
    }
    // 【测什么】坏JSON、字段类型、必填参数、伪装文件均为安全400。
    // 【怎么算红】退回全局处理或未识别绑定异常会返回200/500或泄漏原始输入。
    @Test void malformedRequests() throws Exception {
        for (String body : new String[]{"{bad-secret", "{\"expectedVersion\":\"bad-secret\"}", "{}"})
            mvc.perform(patch("/api/admin/showcase/{id}",UUID.randomUUID()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bad-secret"))));
        mvc.perform(get("/api/showcase").param("current","secret")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/showcase").file(png())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/showcase").param("title","T").param("clientRequestId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/admin/showcase").file(new MockMultipartFile("file","x.png","image/png","<html>secret".getBytes()))
                .param("title","T").param("clientRequestId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(storage);
    }
    // 【测什么】业务大小限制返回真正HTTP413，封面采用独立5MiB上限。
    // 【怎么算红】错用全局异常处理或把封面上限写成200MiB会失败。
    @Test void uploadSizeHttp413() throws Exception {
        var large = new MockMultipartFile("file","x.png","image/png",png().getBytes()) {
            @Override public long getSize() { return 200L*1024*1024+1; }
        };
        var cover = new MockMultipartFile("cover","x.png","image/png",png().getBytes()) {
            @Override public long getSize() { return 5L*1024*1024+1; }
        };
        mvc.perform(multipart("/api/admin/showcase").file(large).param("title","T").param("clientRequestId",UUID.randomUUID().toString()))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/admin/showcase").file(png()).file(cover).param("title","T").param("clientRequestId",UUID.randomUUID().toString()))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(storage);
    }
    // 【测什么】OSS异常安全映射503，不把桶名或凭据返回用户。
    // 【怎么算红】直接抛上游异常给GlobalExceptionHandler将泄漏secret或返回200。
    @Test void safeStorageFailure() throws Exception {
        doThrow(new RuntimeException("secret credentials bucket")).when(storage).put(anyString(),any(),anyString(),anyLong());
        mvc.perform(multipart("/api/admin/showcase").file(png()).param("title","T").param("clientRequestId",UUID.randomUUID().toString()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
}
