package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoredImageReferencesTest {
    // 【测什么】签名只发生于使用阶段，且跨用户、换key、审核和过期均阻断。
    // 【怎么算红】去掉validate里的owner/key/审核/过期任一检查，相应assertThrows失败。
    @Test void signingRevalidatesStoredIdentity() throws Exception {
        var tasks=mock(VideoTaskService.class);var storage=mock(ArtifactStorage.class);
        var source=new VideoTask();source.setUserId(1L);source.setBizTaskId("source");source.setOutputType("IMAGE");source.setStatus("SUCCESS");source.setArtifactStorageType("OSS");source.setArtifactKey("outputs/cat.png");source.setCreateTime(LocalDateTime.now());
        when(tasks.getOne(any(),eq(false))).thenReturn(source);when(storage.exists("outputs/cat.png")).thenReturn(true);when(storage.createSignedGetUrl(eq("outputs/cat.png"),any())).thenReturn("https://signed");
        var service=new StoredImageReferences(tasks,new ContentModerationPolicy(),new ArtifactExpiryPolicy(30),storage);
        var refs=List.of(new StoredImageReferences.Reference("source","outputs/cat.png"));
        service.validate(1L,refs.get(0));verifyNoInteractions(storage);
        assertEquals(List.of("https://signed"),service.signedUrls(1L,refs));
        when(storage.exists("outputs/cat.png")).thenReturn(false);
        assertThrows(Exception.class,()->service.validateAvailable(1L,refs.get(0)));
        when(storage.exists("outputs/cat.png")).thenReturn(true);
        assertThrows(Exception.class,()->service.signedUrls(2L,refs));
        source.setArtifactKey("changed");assertThrows(Exception.class,()->service.signedUrls(1L,refs));source.setArtifactKey("outputs/cat.png");
        source.setCreateTime(LocalDateTime.now().minusDays(31));assertThrows(Exception.class,()->service.signedUrls(1L,refs));source.setCreateTime(LocalDateTime.now());
        source.setModerationStatus("BLOCKED");assertThrows(Exception.class,()->service.signedUrls(1L,refs));source.setModerationStatus("VISIBLE");
        source.setStatus("FAILED");assertThrows(Exception.class,()->service.signedUrls(1L,refs));
    }
    // 【测什么】Worker从持久化引用重建签名URL，不将内部引用或过期短签名传给引擎。
    // 【怎么算红】删除rebuildCommand中stored引用分支，imageUrls断言失败。
    @Test void workerRebuildsReferenceAtUseTime() {
        var resolver=mock(StoredImageReferences.class);
        try { when(resolver.signedUrls(eq(1L),anyList())).thenReturn(List.of("https://fresh-signed")); } catch(Exception e) { throw new RuntimeException(e); }
        var service=new GenerationAttemptService(null,null,null,new com.fasterxml.jackson.databind.ObjectMapper(),new org.example.seedancegenarate.config.VideoCompletionProperties(),null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"storedReferences",resolver);
        var task=new VideoTask();task.setUserId(1L);task.setOutputType("VIDEO");task.setStoredImageReferences("[{\"sourceTaskId\":\"source\",\"objectKey\":\"outputs/cat.png\"}]");
        var attempt=new org.example.seedancegenarate.entity.GenerationAttempt();attempt.setProvider("comfyui");
        var engine=mock(org.example.seedancegenarate.engine.VideoEngine.class);
        org.example.seedancegenarate.engine.GenerateCommand command=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"rebuildCommand",task,attempt,engine);
        assertEquals(List.of("https://fresh-signed"),command.getImageUrls());
        assertNull(task.getImages());assertFalse(task.getStoredImageReferences().contains("signed"));
    }
}
