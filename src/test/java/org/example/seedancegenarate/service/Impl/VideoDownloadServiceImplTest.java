package org.example.seedancegenarate.service.Impl;

import com.sun.net.httpserver.HttpServer;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoDownloadServiceImplTest {
    @TempDir Path temporary;

    private VideoDownloadServiceImpl service(ArtifactStorage storage) {
        return new VideoDownloadServiceImpl(storage, new OssConfig(), new ComfyUiProperties());
    }

    // 【测什么】六种音频从 filename、URL 路径、响应 MIME 均推导正确，octet-stream 用后缀补 MIME。
    // 【怎么算红】遗漏任一音频后缀或 MIME 分支时，该输入错误回退 mp4/bin 或 octet-stream。
    @ParameterizedTest
    @CsvSource({"mp3,audio/mpeg", "m4a,audio/mp4", "aac,audio/aac", "wav,audio/wav",
            "ogg,audio/ogg", "flac,audio/flac"})
    void audioInference(String ext, String mime) {
        var service = service(mock(ArtifactStorage.class));
        String upper = ext.toUpperCase(Locale.ROOT);
        for (String url : List.of("https://example.invalid/view?filename=music%2E" + upper + "&type=output",
                "https://example.invalid/music." + upper + "?token=ignored#fragment")) {
            assertEquals("." + ext, extension(service, url, "application/octet-stream"));
        }
        assertEquals("." + ext, extension(service, "https://example.invalid/view", " " + mime.toUpperCase(Locale.ROOT) + "; charset=binary"));
        assertEquals(mime, type(service, "application/octet-stream", "." + ext));
        assertEquals(mime, type(service, null, "." + ext));
        assertEquals(mime, type(service, mime + "; charset=binary", "." + ext));
    }

    // 【测什么】参数准确匹配、参数优先、无效参数回路径、未知内容及音频 MIME 别名均有确定结果。
    // 【怎么算红】把 notfilename 当 filename、忽略路径回退或未知内容冒充 MP4 时断言失败。
    @Test
    void filenameBoundariesAndUnknownFallback() {
        var service = service(mock(ArtifactStorage.class));
        assertEquals(".m4a", extension(service, "https://example.invalid/song.mp3?filename=music.m4a", null));
        assertEquals(".mp3", extension(service, "https://example.invalid/song.mp3?notfilename=music.wav", null));
        assertEquals(".mp3", extension(service, "https://example.invalid/song.mp3?filename=unknown", null));
        assertEquals(".mp3", extension(service, "https://example.invalid/music%2Emp3", null));
        assertEquals(".bin", extension(service, "https://example.invalid/view?token=abc.mp3", "application/octet-stream"));
        assertEquals(".bin", extension(service, "https://example.invalid/view", "audio/mpeg-invalid"));
        assertEquals("application/octet-stream", type(service, "application/octet-stream", ".bin"));
        assertEquals("application/octet-stream", type(service, null, ".unknown"));
        assertEquals(".wav", extension(service, "https://example.invalid/view", "audio/x-wav"));
        assertEquals(".flac", extension(service, "https://example.invalid/view", "audio/x-flac"));
        assertEquals(".m4a", extension(service, "https://example.invalid/view", "audio/x-m4a"));
        assertEquals(".ogg", extension(service, "https://example.invalid/view", "application/ogg"));
        assertEquals(".mp4", extension(service, "https://example.invalid/view", "video/mp4"));
        assertEquals("image/png", type(service, null, ".png"));
    }

    // 【测什么】loopback 假 HTTP 经真正 download/storeArtifact 将推断后缀、MIME、字节交给假 OSS put。
    // 【怎么算红】download 漏传推断值、将音频命名 MP4 或 put 类型错误时精确参数断言失败。
    @ParameterizedTest
    @CsvSource({"mp3,audio/mpeg", "m4a,audio/mp4", "aac,audio/aac", "wav,audio/wav",
            "ogg,audio/ogg", "flac,audio/flac", "bin,application/octet-stream"})
    void downloadPassesCorrectTypeToOss(String ext, String mime) throws Exception {
        ArtifactStorage storage = mock(ArtifactStorage.class);
        var service = spy(service(storage));
        Path staging = temporary.resolve("download." + ext);
        doReturn(staging).when(service).createTempFile("." + ext);
        byte[] bytes = {1, 2, 3, 4};
        String key = "outputs/tsk_audio/attempt-7/result." + ext;
        when(storage.put(eq(key), any(InputStream.class), eq(mime), eq(4L))).thenAnswer(call -> {
            assertArrayEquals(bytes, call.<InputStream>getArgument(1).readAllBytes());
            return new ArtifactStorage.StoredArtifact(key, mime, 4L, "test-etag");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type",
                        exchange.getRequestURI().getPath().equals("/typed") ? mime + "; charset=binary" : "application/octet-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String path : List.of("/view?filename=music%2E" + ext, "/music." + ext, "/typed")) {
                var result = service.download(base + path, "tsk_audio", 7L, "seedance");
                assertEquals("tsk_audio." + ext, result.mediaName());
                assertEquals(mime, result.artifact().contentType());
                assertEquals(key, result.artifact().objectKey());
                assertFalse(Files.exists(staging), "Worker 临时文件必须清理");
            }
            verify(storage, times(3)).put(eq(key), any(InputStream.class), eq(mime), eq(4L));
            verifyNoMoreInteractions(storage);
        } finally {
            server.stop(0);
        }
    }

    private String extension(VideoDownloadServiceImpl service, String url, String mime) {
        return ReflectionTestUtils.invokeMethod(service, "resolveExtension", url, mime);
    }

    private String type(VideoDownloadServiceImpl service, String mime, String extension) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeContentType", mime, extension);
    }
}
