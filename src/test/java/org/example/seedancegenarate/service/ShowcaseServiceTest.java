package org.example.seedancegenarate.service;

import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.ShowcaseWork.Edit;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShowcaseServiceTest {
    JdbcTemplate db;
    ShowcaseStorage storage;
    ShowcaseService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V55__showcase_work.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        storage = mock(ShowcaseStorage.class);
        service = new ShowcaseService(db, storage);
        login("ADMIN");
    }
    @AfterEach void clear() { UserContext.clear(); }
    static void login(String role) {
        var user = new AppUser(); user.setId(1L); user.setRole(role); UserContext.setUser(user);
    }
    static MockMultipartFile png() {
        return new MockMultipartFile("file", "a.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10,1});
    }
    static void status(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
    // 【测什么】草稿仅管理可读，发布才公开，下架撤销新签名，版本冲突不覆盖。
    // 【怎么算红】去掉公开状态过滤或CAS条件，这条会失败。
    @Test void lifecycleAndCas() {
        var work = service.create(UUID.randomUUID().toString(), "Title", "desc", png(), null);
        assertEquals("DRAFT", work.status());
        assertEquals(0, service.publicPage(1, 12).total());
        status(404, () -> service.publicMedia(work.id(), false));
        var published = service.edit(work.id(), new Edit("New", "description", 7, 0L, "PUBLISHED"));
        assertEquals(1, published.version()); assertNotNull(published.publishedAt());
        login("USER");
        assertEquals(1, service.publicPage(1, 12).total());
        when(storage.sign(anyString())).thenReturn(URI.create("https://example.test/media"));
        assertEquals("https://example.test/media", service.publicMedia(work.id(), false).toString());
        status(404, () -> service.publicMedia(work.id(), true));
        login("ADMIN");
        status(409, () -> service.edit(work.id(), new Edit("Lost", null, null, 0L, null)));
        service.edit(work.id(), new Edit(null, null, null, 1L, "OFFLINE"));
        status(404, () -> service.publicMedia(work.id(), false));
        assertEquals(0, service.publicPage(1, 12).total());
        assertEquals(1, service.adminPage(1, 12).total());
    }
    // 【测什么】同请求同内容重试复用记录，异内容在OSS之前409。
    // 【怎么算红】移除哈希比对或重放查询，将产生额外上传或错误接受。
    @Test void retryAndHashBeforeUpload() {
        String request = UUID.randomUUID().toString();
        var first = service.create(request, "Title", "", png(), null);
        assertEquals(first.id(), service.create(request, "Title", "", png(), null).id());
        status(409, () -> service.create(request, "Other", "", png(), null));
        verify(storage, times(1)).put(anyString(), any(), anyString(), anyLong());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM showcase_work", Integer.class));
    }
    // 【测什么】两个同时上传的相同请求只留下一个作品，返回相同ID。
    // 【怎么算红】删除数据库请求唯一约束会出现两行和两个不同ID。
    @Test void concurrentReplay() throws Exception { raceUpload(false); }
    // 【测什么】同时上传的异内容同请求只有一个成功，另一个409且不覆盖媒体。
    // 【怎么算红】移除冲突后的哈希比对会错误接受第二个请求。
    @Test void concurrentDifferentContent() throws Exception { raceUpload(true); }
    private void raceUpload(boolean different) throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var keys = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            keys.add(call.getArgument(0));
            assertEquals(9, ((java.io.InputStream)call.getArgument(1)).readAllBytes().length);
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); return null;
        }).when(storage).put(anyString(), any(), anyString(), anyLong());
        String request = UUID.randomUUID().toString();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {
                login("ADMIN");
                try { return service.create(request, different ? "Title" + i : "Title", "", png(), null).id(); }
                catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); return "conflict"; }
                finally { UserContext.clear(); }
            })).toList();
            String a = tasks.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
            String b = tasks.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (different) assertEquals(1, java.util.stream.Stream.of(a,b).filter("conflict"::equals).count());
            else assertEquals(a, b);
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM showcase_work", Integer.class));
            assertEquals(different ? 2 : 1, keys.size());
            assertTrue(keys.stream().allMatch(k -> k.matches("showcase/1/" + request + "/[a-f0-9]{64}/media.png")));
        } finally { pool.shutdownNow(); }
    }
    // 【测什么】并发相同版本编辑恰好一胜，内容和版本不会丢失更新。
    // 【怎么算红】删掉UPDATE版本条件会有两个成功结果。
    @Test void concurrentCas() throws Exception {
        var work = service.create(UUID.randomUUID().toString(), "Original", "", png(), null);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {
                login("ADMIN");
                try {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    service.edit(work.id(), new Edit("Editor" + i, null, null, 0L, null)); return true;
                } catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); return false; }
                finally { UserContext.clear(); }
            })).toList();
            assertNotEquals(tasks.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS), tasks.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, service.adminPage(1,12).records().get(0).version());
        } finally { pool.shutdownNow(); }
    }
    // 【测什么】标题、UUID、简介、分页和编辑非法值均为400且无存储副作用。
    // 【怎么算红】去掉对应输入校验会进入存储或接受非法编辑。
    @Test void invalidFields() {
        String id = UUID.randomUUID().toString();
        for (String title : new String[]{"", "  ", "x".repeat(129)})
            status(400, () -> service.create(id, title, "", png(), null));
        status(400, () -> service.create("1-1-1-1-1", "T", "", png(), null));
        status(400, () -> service.create(id, "T", "x".repeat(2001), png(), null));
        status(400, () -> service.publicPage(0, 12));
        status(400, () -> service.publicPage(Long.MAX_VALUE, 12));
        status(400, () -> service.publicPage(1, 101));
        status(400, () -> service.edit(id, new Edit(null, null, -1, 0L, null)));
        status(400, () -> service.edit(id, new Edit(null, null, 1000000, 0L, null)));
        status(400, () -> service.edit(id, new Edit(null, null, null, null, "PUBLISHED")));
        status(400, () -> service.edit(id, new Edit(null, null, null, 0L, "DELETED")));
        verifyNoInteractions(storage);
    }
    // 【测什么】上传失败不落可见行，重试原请求可成功，错误不泄漏上游内容。
    // 【怎么算红】先INSERT后上传会留下失败草稿，透传异常将暴露secret。
    @Test void uploadFailureAndTransactionBoundary() {
        doThrow(new RuntimeException("secret bucket credential")).when(storage).put(anyString(), any(), anyString(), anyLong());
        String id = UUID.randomUUID().toString();
        var failure = assertThrows(ResponseStatusException.class, () -> service.create(id, "T", "", png(), null));
        assertFalse(failure.getMessage().contains("secret"));
        assertEquals(0, service.adminPage(1,12).total());
        reset(storage);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        tx.executeWithoutResult(s -> status(409, () -> service.create(id, "T", "", png(), null)));
        verifyNoInteractions(storage);
        service.create(id, "T", "", png(), null);
        assertEquals(1, service.adminPage(1,12).total());
    }
    // 【测什么】扩展名和文件头均校验，封面禁止视频，空文件、假报长度均拒绝。
    // 【怎么算红】仅检查后缀或信任声明长度会接受伪装内容。
    @Test void fileValidation() throws Exception {
        String id = UUID.randomUUID().toString();
        for (var file : java.util.List.of(
                new MockMultipartFile("file", "x.png", "image/png", "<html>bad</html>".getBytes()),
                new MockMultipartFile("file", "x.mp4", "video/mp4", png().getBytes()),
                new MockMultipartFile("file", "x.svg", "image/png", png().getBytes()),
                new MockMultipartFile("file", "x.png", "image/png", new byte[0])))
            status(400, () -> service.create(id, "T", "", file, null));
        var mp4 = new MockMultipartFile("file", "x.mp4", "video/mp4", mp4Header());
        status(400, () -> service.create(id, "T", "", png(), mp4));
        status(400, () -> service.create(id, "T", "", streaming(16, 17, "png"), null));
        status(413, () -> service.create(id, "T", "", streaming(200L*1024*1024+1, 9, "png"), null));
        status(413, () -> service.create(id, "T", "", png(), streaming(5L*1024*1024+1, 9, "png")));
        status(413, () -> service.create(id, "T", "", streaming(9, 200L*1024*1024+1, "png"), null));
        verifyNoInteractions(storage);
    }
    // 【测什么】主文件恰好200MiB、封面5MiB流式通过，JPEG与MP4被正确分类。
    // 【怎么算红】把上限改为严格小于或误拒支持格式会失败；调用getBytes也会失败。
    @Test void exactSizeAndFormats() throws Exception {
        doAnswer(call -> {
            assertEquals((long)call.getArgument(3), ((java.io.InputStream)call.getArgument(1)).transferTo(java.io.OutputStream.nullOutputStream()));
            return null;
        }).when(storage).put(anyString(), any(), anyString(), anyLong());
        var work = service.create(UUID.randomUUID().toString(), "x".repeat(128), "x".repeat(2000),
                streaming(200L*1024*1024, 200L*1024*1024, "mp4"), streaming(5L*1024*1024, 5L*1024*1024, "png"));
        assertEquals("VIDEO", work.mediaType()); assertTrue(work.hasCover());
        var jpeg = new MockMultipartFile("file", "a.JPEG", "application/octet-stream", new byte[]{(byte)255,(byte)216,(byte)255,1});
        assertEquals("IMAGE", service.create(UUID.randomUUID().toString(), "J", "", jpeg, null).mediaType());
    }
    static byte[] mp4Header() {
        return new byte[]{0,0,0,16,102,116,121,112,105,115,111,109,0,0,0,0};
    }
    // 【测什么】qt容器无论mp4/mov扩展名均存正确MIME与后缀，重试不重复上传。
    // 【怎么算红】拒qt、错误分类为图片、存video/mp4或放开伪造头/视频封面会失败。
    @Test void quickTimeContainerUsesActualMediaType() {
        byte[] qt = new byte[]{0,0,0,20,102,116,121,112,113,116,32,32,0,0,0,0,113,116,32,32};
        String request = UUID.randomUUID().toString();
        var file = new MockMultipartFile("file", "clip.mp4", "video/mp4", qt);
        var work = service.create(request, "QT", "", file, null);
        assertEquals("VIDEO", work.mediaType());
        verify(storage).put(endsWith("/media.mov"), any(), eq("video/quicktime"), eq(20L));
        var renamed = new MockMultipartFile("file", "clip.mov", "application/octet-stream", qt);
        assertEquals(work.id(), service.create(request, "QT", "", renamed, null).id());
        verify(storage, times(1)).put(anyString(), any(), anyString(), anyLong());
        status(400, () -> service.create(UUID.randomUUID().toString(), "QT", "", file, renamed));
        byte[] bad = qt.clone(); bad[3] = 99;
        status(400, () -> service.create(UUID.randomUUID().toString(), "QT", "",
                new MockMultipartFile("file", "clip.mov", "video/quicktime", bad), null));
        byte[] unknown = qt.clone(); unknown[8] = 120;
        status(400, () -> service.create(UUID.randomUUID().toString(), "QT", "",
                new MockMultipartFile("file", "clip.mov", "video/quicktime", unknown), null));
    }
    static org.springframework.web.multipart.MultipartFile streaming(long declared, long actual, String ext) throws Exception {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getSize()).thenReturn(declared);
        when(file.getOriginalFilename()).thenReturn("file." + ext);
        when(file.getBytes()).thenThrow(new AssertionError("Must stream"));
        byte[] header = ext.equals("mp4") ? mp4Header() : png().getBytes();
        when(file.getInputStream()).thenAnswer(c -> new java.io.InputStream() {
            long position;
            public int read() { return position >= actual ? -1 : position < header.length ? header[(int)position++] & 255 : advance(); }
            int advance() { position++; return 0; }
            public int read(byte[] b, int offset, int length) {
                if (length == 0) return 0;
                if (position >= actual) return -1;
                int n = (int)Math.min(length, actual-position);
                java.util.Arrays.fill(b, offset, offset+n, (byte)0);
                for (int i=0; i<n && position+i<header.length; i++) b[offset+i]=header[(int)position+i];
                position += n; return n;
            }
        });
        return file;
    }
    // 【测什么】排序按sortOrder、发布时间、ID稳定，分页不重复。
    // 【怎么算红】删掉任一排序键会改变预定结果。
    @Test void stableOrdering() {
        var a = service.create(UUID.randomUUID().toString(), "A", "", png(), null);
        var b = service.create(UUID.randomUUID().toString(), "B", "", png(), null);
        service.edit(a.id(), new Edit(null,null,0,0L,"PUBLISHED"));
        service.edit(b.id(), new Edit(null,null,1,0L,"PUBLISHED"));
        assertEquals(b.id(), service.publicPage(1,1).records().get(0).id());
        service.edit(a.id(), new Edit(null,null,1,1L,null));
        db.update("UPDATE showcase_work SET published_at='2026-01-01 00:00:00' WHERE id=?", a.id());
        db.update("UPDATE showcase_work SET published_at='2026-01-02 00:00:00' WHERE id=?", b.id());
        assertEquals(b.id(), service.publicPage(1,1).records().get(0).id());
        db.update("UPDATE showcase_work SET published_at='2026-01-01 00:00:00'");
        String first = a.id().compareTo(b.id()) > 0 ? a.id() : b.id();
        assertEquals(first, service.publicPage(1,1).records().get(0).id());
        assertNotEquals(first, service.publicPage(2,1).records().get(0).id());
    }
    // 【测什么】DB落库失败不留下作品，原请求重试复用确定性对象路径。
    // 【怎么算红】随机对象路径或吞下DB失败会导致路径不同或错误成功。
    @Test void databaseFailureRetryUsesSameObject() {
        db.execute("ALTER TABLE showcase_work ADD CONSTRAINT reject_title CHECK(title <> 'T')");
        String request = UUID.randomUUID().toString();
        status(503, () -> service.create(request, "T", "", png(), null));
        assertEquals(0, service.adminPage(1,12).total());
        db.execute("ALTER TABLE showcase_work DROP CONSTRAINT reject_title");
        service.create(request, "T", "", png(), null);
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).put(keys.capture(), any(), anyString(), anyLong());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }
    // 【测什么】文件字节或封面变化必须改变请求指纹，在已存在请求上上传前409。
    // 【怎么算红】哈希不含媒体字节或封面会把异内容错误当作重放。
    @Test void hashIncludesMediaAndCoverBytes() throws Exception {
        String request = UUID.randomUUID().toString();
        service.create(request, "T", "", png(), png());
        byte[] bytes = png().getBytes(); bytes[8] = 2;
        var changed = new MockMultipartFile("file", "a.png", "image/png", bytes);
        status(409, () -> service.create(request, "T", "", changed, png()));
        status(409, () -> service.create(request, "T", "", png(), changed));
        status(409, () -> service.create(request, "T", "", png(), null));
        verify(storage, times(2)).put(anyString(), any(), anyString(), anyLong());
    }
    // 【测什么】所有服务入口检查登录或管理员，即使绕开HTTP。
    // 【怎么算红】删除服务门禁使任一入口被匿名或普通用户调用成功。
    @Test void guards() {
        UserContext.clear();
        status(401, () -> service.publicPage(1, 12));
        status(401, () -> service.publicMedia("x", false));
        status(401, () -> service.adminPage(1, 12));
        login("USER");
        status(403, () -> service.create(null, null, null, null, null));
        status(403, () -> service.adminPage(1, 12));
        status(403, () -> service.edit("x", null));
        status(403, () -> service.adminMedia("x", false));
        verifyNoInteractions(storage);
    }
}
