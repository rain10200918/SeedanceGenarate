package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class VideoDownloadAttemptKeyTest {

    @TempDir
    Path tempDir;

    @Test
    void objectKeyIsStableWithinAttemptAndIsolatedAcrossAttempts() {
        // 【测什么】同 attempt 重放写同 key，不同 attempt（含 legacy）绝不覆盖同一 OSS object。
        // 【怎么算红】key 只含 bizTaskId 时，旧 Worker 晚上传会覆盖新 attempt 已提交的产物。
        OssConfig config = new OssConfig();
        config.setArtifactPrefix("outputs");
        VideoDownloadServiceImpl service = new VideoDownloadServiceImpl(
                mock(ArtifactStorage.class), config, new ComfyUiProperties());

        String first = service.artifactObjectKey("tsk_1", 7L, ".mp4");
        String replay = service.artifactObjectKey("tsk_1", 7L, ".mp4");
        String next = service.artifactObjectKey("tsk_1", 8L, ".mp4");
        String legacy = service.artifactObjectKey("tsk_1", null, ".mp4");

        assertEquals(first, replay);
        assertEquals("outputs/tsk_1/attempt-7/result.mp4", first);
        assertNotEquals(first, next);
        assertNotEquals(first, legacy);
    }

    @Test
    void copyFailureStillDeletesPartialTempFile() throws Exception {
        // 【测什么】远端流在 Files.copy 中途失败时，已创建的 dl-* 临时文件仍在 finally 删除。
        // 【怎么算红】finally 只包 OSS put 时，copy 异常会在 /tmp 永久泄漏大视频残片。
        VideoDownloadServiceImpl service = spy(new VideoDownloadServiceImpl(
                mock(ArtifactStorage.class), new OssConfig(), new ComfyUiProperties()));
        Path partial = tempDir.resolve("dl-partial.mp4");
        doReturn(partial).when(service).createTempFile(".mp4");
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };

        assertThrows(IOException.class, () -> service.storeArtifact(
                broken, "tsk_1", 7L, ".mp4", "video/mp4"));

        assertFalse(Files.exists(partial), "copy 失败也不能遗留临时文件");
    }

    @Test
    void comfyTokenIsScopedToExplicitComfyProvider() {
        // 【测什么】内部 X-Comfy-Token 只允许发给 provider=comfyui 的下载请求。
        // 【怎么算红】按“只要配置 token 就加 header”会把令牌泄给 Seedance 公网产物主机。
        VideoDownloadServiceImpl service = new VideoDownloadServiceImpl(
                mock(ArtifactStorage.class), new OssConfig(), new ComfyUiProperties());

        assertTrue(service.shouldAttachComfyToken("comfyui"));
        assertTrue(service.shouldAttachComfyToken(" COMFYUI "));
        assertFalse(service.shouldAttachComfyToken("seedance"));
        assertFalse(service.shouldAttachComfyToken(null));
    }
}
