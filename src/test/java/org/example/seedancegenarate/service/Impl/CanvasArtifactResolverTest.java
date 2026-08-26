package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布产物交接守卫：<b>上游生成的东西，下游引擎必须真的拉得到</b>。
 * 这条破了就是「提交成功 → 冻结钱 → 四分钟后引擎报网络错」，用户完全看不懂发生了什么。
 */
class CanvasArtifactResolverTest {

    private VideoTaskService videoTaskService;
    private ArtifactStorage artifactStorage;
    private CanvasArtifactResolverImpl resolver;

    @BeforeEach
    void setUp() {
        videoTaskService = mock(VideoTaskService.class);
        artifactStorage = mock(ArtifactStorage.class);
        OssConfig ossConfig = new OssConfig();
        ossConfig.setSignedUrlTtlSeconds(300);
        resolver = new CanvasArtifactResolverImpl(videoTaskService, artifactStorage, ossConfig);
    }

    private CanvasNode producer(String taskId) {
        CanvasNode n = new CanvasNode();
        n.setId(2L);
        n.setTitle("生成关键帧");
        n.setTaskId(taskId);
        return n;
    }

    @Test
    void httpUrlPassesThroughUntouched() throws Exception {
        // 测什么：素材节点给的公网地址原样透传，不去查库、不去签名
        // 怎么算红：对已经能下载的地址也去反查任务 —— 素材节点根本没有 taskId，会被误判成「无法作为输入」，
        //          「拖一张图接到生成节点」这个最基本的用法直接不可用
        ResolvedInputs.PortValue in = new ResolvedInputs.PortValue(
                MediaType.IMAGE, "https://oss.example.com/images/a.png");

        assertSame(in, resolver.toFetchable(producer(null), in));
        verify(videoTaskService, never()).getOne(any(), anyBoolean());
        verify(artifactStorage, never()).createSignedGetUrl(any(), any());
    }

    @Test
    void textValueIsNotTreatedAsUrl() throws Exception {
        // 测什么：文本口传的是内容本身，不能被当成地址去解析
        // 怎么算红：把提示词拿去查任务 —— 文本节点接到提示词口就永远报错，整条链跑不起来
        ResolvedInputs.PortValue in = new ResolvedInputs.PortValue(MediaType.TEXT, "赛博朋克，雨夜霓虹");

        assertSame(in, resolver.toFetchable(producer(null), in));
        verify(artifactStorage, never()).createSignedGetUrl(any(), any());
    }

    @Test
    void artifactKeyBecomesSignedUrl() throws Exception {
        // 测什么：生成节点落库的产物 key → 反查任务 → 用 artifact_key 现签一个短期地址
        // 怎么算红：key 原样交给引擎 —— ComfyUI downloadBytes("tsk_up.png") 必炸
        VideoTask task = new VideoTask();
        task.setArtifactStorageType("OSS");
        task.setArtifactKey("outputs/tsk_up/result.png");
        when(videoTaskService.getOne(any(), anyBoolean())).thenReturn(task);
        when(artifactStorage.createSignedGetUrl(eq("outputs/tsk_up/result.png"), any()))
                .thenReturn("https://oss/outputs/tsk_up/result.png?Expires=1&Signature=s");

        ResolvedInputs.PortValue out = resolver.toFetchable(producer("tsk_up"),
                new ResolvedInputs.PortValue(MediaType.IMAGE, "tsk_up.png"));

        assertEquals("https://oss/outputs/tsk_up/result.png?Expires=1&Signature=s", out.value());
        assertEquals(MediaType.IMAGE, out.mediaType(), "媒体类型不能在解析中丢失");
        verify(artifactStorage).createSignedGetUrl("outputs/tsk_up/result.png", Duration.ofSeconds(300));
    }

    @Test
    void missingArtifactIsRejectedNotSubmitted() throws Exception {
        // 测什么：任务查不到 / 产物没进对象存储时，明确抛错（调用方据此标 FAILED，不提交）
        // 怎么算红：返回原值或 null —— 要么带着废地址去提交白冻结钱，要么下游静默少一张参考图，
        //          生成结果和用户画的连线对不上
        when(videoTaskService.getOne(any(), anyBoolean())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () ->
                resolver.toFetchable(producer("tsk_up"),
                        new ResolvedInputs.PortValue(MediaType.IMAGE, "tsk_up.png")));

        assertTrue(e.getMessage().contains("生成关键帧"), "报错要指名是哪个上游节点，实际: " + e.getMessage());
        verify(artifactStorage, never()).createSignedGetUrl(any(), any());
    }

    @Test
    void localOnlyArtifactIsRejected() throws Exception {
        // 测什么：产物只存在本地（非 OSS）时同样拒绝——本地路径给不出引擎能访问的地址
        // 怎么算红：拿本地路径当地址提交，引擎照样下载失败，错误延后到扣完钱之后才暴露
        VideoTask task = new VideoTask();
        task.setArtifactStorageType("LOCAL");
        task.setArtifactKey(null);
        when(videoTaskService.getOne(any(), anyBoolean())).thenReturn(task);

        assertThrows(BusinessException.class, () ->
                resolver.toFetchable(producer("tsk_up"),
                        new ResolvedInputs.PortValue(MediaType.IMAGE, "tsk_up.png")));
        verify(artifactStorage, never()).createSignedGetUrl(any(), any());
    }
}
