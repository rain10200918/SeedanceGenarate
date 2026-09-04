package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.dto.SendMessageRequest;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话参考素材和生成页同一套：本地文件传 OSS、历史地址只认本系统域名、图片按 imageOrder 归并、视频音频本地在前。
 */
class ConversationMediaResolverTest {

    private static final String OWN = "https://hszs-generate-api.oss-cn-beijing.aliyuncs.com/images/";

    private final OssService oss = mock(OssService.class);
    private final OssConfig ossConfig = new OssConfig();
    private ConversationMediaResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        ossConfig.setDomain("https://hszs-generate-api.oss-cn-beijing.aliyuncs.com");
        // 传什么文件就回什么名字的地址，方便看顺序
        when(oss.upload(any(MultipartFile.class))).thenAnswer(inv -> OWN + ((MultipartFile) inv.getArgument(0)).getOriginalFilename());
        resolver = new ConversationMediaResolver(oss, ossConfig);
    }

    @Test
    void foreignHostsAreRejectedOwnStoragePasses() {
        // 【测什么】历史地址白名单：引擎会去下载这些地址，别人家的域名 / 内网地址一律 400（和 /video/image2video 一个规矩）
        // 【怎么算红】去掉 host 比对，只查 http(s)
        List<SendMessageRequest.Attachment> ok = resolver.resolve(
                List.of(new SendMessageRequest.Attachment("image", OWN + "a.png")), ConversationMediaResolver.LocalFiles.none());
        assertEquals(OWN + "a.png", ok.get(0).url());

        BusinessException evil = assertThrows(BusinessException.class, () -> resolver.resolve(
                List.of(new SendMessageRequest.Attachment("image", "https://evil.example/a.png")), null));
        assertEquals(400, evil.getCode());
        assertTrue(evil.getMessage().contains("本系统存储"), evil.getMessage());

        BusinessException intranet = assertThrows(BusinessException.class, () -> resolver.resolve(
                List.of(new SendMessageRequest.Attachment("video", "http://10.0.0.5/admin")), null));
        assertTrue(intranet.getMessage().contains("视频"), "报错要说清是哪类素材: " + intranet.getMessage());

        assertThrows(BusinessException.class, () -> resolver.resolve(
                List.of(new SendMessageRequest.Attachment("audio", "ftp://hszs-generate-api.oss-cn-beijing.aliyuncs.com/x.mp3")), null));
    }

    @Test
    void withoutADomainConfiguredOnlySchemeIsChecked() {
        // 【测什么】本地开发没配 OSS 域名：只挡非 http(s)，别把开发环境卡死
        ossConfig.setDomain(null);
        List<SendMessageRequest.Attachment> out = resolver.resolve(
                List.of(new SendMessageRequest.Attachment("image", "http://localhost:8080/api/video/x.png")), null);
        assertEquals(1, out.size());
    }

    @Test
    void localImagesMergeByImageOrderAndVideosGoFirst() {
        // 【测什么】imageOrder 逐位置 file/url 决定 <Picture N> 的编号；视频 / 音频固定本地在前、历史在后
        // 【怎么算红】归并忽略 imageOrder（本地全排前面）
        MultipartFile f1 = file("1.png", "image/png");
        MultipartFile f2 = file("2.png", "image/png");
        MultipartFile v1 = file("v.mp4", "video/mp4");
        List<SendMessageRequest.Attachment> refs = List.of(
                new SendMessageRequest.Attachment("image", OWN + "old.png"),
                new SendMessageRequest.Attachment("video", OWN + "old.mp4"),
                new SendMessageRequest.Attachment("audio", OWN + "old.mp3"));

        List<SendMessageRequest.Attachment> out = resolver.resolve(refs, new ConversationMediaResolver.LocalFiles(
                new MultipartFile[]{f1, f2}, List.of("url", "file", "file"), new MultipartFile[]{v1}, null));

        assertEquals(List.of(
                "image:" + OWN + "old.png", "image:" + OWN + "1.png", "image:" + OWN + "2.png",
                "video:" + OWN + "v.mp4", "video:" + OWN + "old.mp4",
                "audio:" + OWN + "old.mp3"), out.stream().map(a -> a.type() + ":" + a.url()).toList());
    }

    @Test
    void brokenImageOrderFallsBackToLocalFirstAndEmptyPartsAreSkipped() throws Exception {
        // 【测什么】imageOrder 数量对不上就退回「本地在前、历史在后」；空的 multipart 段不传 OSS
        MultipartFile f1 = file("1.png", "image/png");
        MultipartFile empty = new MockMultipartFile("images", "", "image/png", new byte[0]);
        List<SendMessageRequest.Attachment> refs = List.of(new SendMessageRequest.Attachment("image", OWN + "old.png"));

        List<SendMessageRequest.Attachment> out = resolver.resolve(refs, new ConversationMediaResolver.LocalFiles(
                new MultipartFile[]{f1, empty}, List.of("url"), null, null));

        assertEquals(List.of(OWN + "1.png", OWN + "old.png"), out.stream().map(SendMessageRequest.Attachment::url).toList());
        verify(oss, never()).upload(empty);
    }

    @Test
    void ossFailureBecomesAPlainUserFacingError() throws Exception {
        // 【测什么】OSS 挂了：给用户一句人话，不把 SDK 异常原样甩出去
        when(oss.upload(any(MultipartFile.class))).thenThrow(new RuntimeException("OSS 403 SignatureDoesNotMatch key=AKID..."));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> resolver.resolve(List.of(),
                new ConversationMediaResolver.LocalFiles(new MultipartFile[]{file("1.png", "image/png")}, null, null, null)));
        assertEquals("参考素材上传失败，请稍后再试", e.getMessage());
    }

    private static MultipartFile file(String name, String type) {
        return new MockMultipartFile("images", name, type, new byte[]{1, 2, 3});
    }
}
