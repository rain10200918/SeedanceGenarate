package org.example.seedancegenarate.service;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShowcaseResponsePerformanceTest {
    private static final String ID = "ABCDEF12-3456-7890-ABCD-1234567890AB";
    private static final String PUBLIC_SQL = "SELECT media_key,cover_key FROM showcase_work WHERE id=? AND status='PUBLISHED'";
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ShowcaseStorage storage = mock(ShowcaseStorage.class);
    private ShowcaseService service;

    @BeforeEach void setup() {
        service = new ShowcaseService(db, storage);
        var user = new AppUser();
        user.setId(1L);
        user.setRole("USER");
        UserContext.setUser(user);
    }

    @AfterEach void clear() { UserContext.clear(); }

    // 【测什么】20次精选媒体读取复用已编译正则，仍逐次查询发布状态和签名，无跨请求媒体缓存。
    // 【怎么算红】恢复String.matches或每请求Pattern.compile后，compile零次断言失败；缓存媒体则SQL/签名次数失败。
    @Test void repeatedReadsDoNotRecompileUuidPattern() {
        when(db.query(eq(PUBLIC_SQL), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(ID)))
                .thenReturn(List.of("showcase/media.mp4"));
        URI signed = URI.create("https://example.test/media");
        when(storage.sign("showcase/media.mp4")).thenReturn(signed);
        // Service类已初始化；只计请求期间编译，保留JDK真实匹配行为。
        try (var patterns = mockStatic(Pattern.class, CALLS_REAL_METHODS)) {
            for (int i = 0; i < 20; i++) assertEquals(signed, service.publicMedia(ID, false));
            patterns.verify(() -> Pattern.compile(anyString()), never());
            patterns.verify(() -> Pattern.compile(anyString(), anyInt()), never());
        }
        verify(db, times(20)).query(eq(PUBLIC_SQL), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(ID));
        verify(storage, times(20)).sign("showcase/media.mp4");
    }

    // 【测什么】UUID仍要求完整ASCII十六进制格式；null、短格式、前后空白、非法字符均在SQL之前400。
    // 【怎么算红】改为仅UUID.fromString、trim或find匹配会错误接受短格式/空白/包裹字符串。
    @Test void malformedIdsNeverReachDatabase() {
        for (String id : Arrays.asList(null, "", "1-1-1-1-1", " " + ID, ID + "\n",
                ID.replace('A', 'G'), ID.replace('1', '１'), "x" + ID + "x")) {
            var error = assertThrows(ResponseStatusException.class, () -> service.publicMedia(id, false));
            assertEquals(400, error.getStatusCode().value());
            assertEquals("请求编号无效", error.getReason());
        }
        verifyNoInteractions(db, storage);
    }

    // 【测什么】合法大小写UUID保持原查询参数；封面选cover_key，媒体选media_key；公开读取保留发布过滤。
    // 【怎么算红】把查询ID小写化、漏发布过滤或两种读取都取media_key，返回地址/精确SQL断言失败。
    @Test void validIdsAndCoverSelectionKeepOriginalSemantics() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("media_key")).thenReturn("showcase/media.mp4");
        when(row.getString("cover_key")).thenReturn("showcase/cover.png");
        for (String id : List.of(ID, ID.toLowerCase(java.util.Locale.ROOT))) {
            when(db.query(eq(PUBLIC_SQL), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(id)))
                    .thenAnswer(call -> List.of(call.<RowMapper<String>>getArgument(1).mapRow(row, 0)));
            when(storage.sign(anyString())).thenAnswer(call -> URI.create("https://example.test/" + call.getArgument(0)));
            assertEquals(URI.create("https://example.test/showcase/media.mp4"), service.publicMedia(id, false));
            assertEquals(URI.create("https://example.test/showcase/cover.png"), service.publicMedia(id, true));
            verify(db, times(2)).query(eq(PUBLIC_SQL), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(id));
        }
    }

    // 【测什么】未登录401优先、普通用户管理读取403、不存在404、签名失败503，失败不自动重试。
    // 【怎么算红】删除访问检查、吞查询空结果或重试签名，状态码或调用次数断言失败。
    @Test void accessAndFailureBehaviorRemainUnchanged() {
        UserContext.clear();
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.publicMedia("invalid", false)).getStatusCode().value());
        setup();
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.adminMedia(ID, false)).getStatusCode().value());
        verifyNoInteractions(db, storage);
        when(db.query(eq(PUBLIC_SQL), org.mockito.ArgumentMatchers.<RowMapper<String>>any(), eq(ID)))
                .thenReturn(List.of(), List.of("showcase/media.mp4"));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.publicMedia(ID, false)).getStatusCode().value());
        when(storage.sign("showcase/media.mp4")).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.publicMedia(ID, false)).getStatusCode().value());
        verify(storage).sign("showcase/media.mp4");
    }
}
