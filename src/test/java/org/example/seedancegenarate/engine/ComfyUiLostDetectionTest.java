package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.Impl.ComfyUiEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ComfyUI 作业丢失判定守卫。
 * <p>
 * 背景：ComfyUI 队列是内存态，进程重启即清空。此时 {@code /history/{id}} 返回空，
 * 与「还在排队」的响应<b>完全相同</b>——只查 history 分不出来，任务就会一直挂在 PROCESSING
 * 直到 60 分钟超龄兜底。2026-08-26 线上 video_task 1180 白等了一小时，重投后 6 分半就跑完。
 */
class ComfyUiLostDetectionTest {

    private static final String PROMPT = "436120e1-8199-4a16-b98a-0bb11f8186d8";
    private static final String BASE = "http://node/gpu-3";

    private ComfyUiClient client;
    private ComfyUiEngine engine;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = mock(ComfyUiClient.class);
        ComfyUiProperties props = new ComfyUiProperties();
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId("gpu-3");
        node.setBaseUrl(BASE);
        node.setEnabled(true);
        props.setNodes(List.of(node));
        engine = new ComfyUiEngine(props, client, mock(ComfyUiNodeScheduler.class), new org.example.seedancegenarate.engine.comfyui.ComfyUiFleet(props),
                List.<org.example.seedancegenarate.engine.comfyui.WorkflowBuilder>of(),
                json, new org.example.seedancegenarate.config.VideoCompletionProperties());
    }

    private VideoTask task() {
        VideoTask t = new VideoTask();
        t.setId(1180L);
        t.setBizTaskId("tsk_bb3f99fb");
        t.setNodeId("gpu-3");
        t.setProviderTaskId(PROMPT);
        t.setStatus("PROCESSING");
        t.setModel("minimax-h3-hd");
        return t;
    }

    private com.fasterxml.jackson.databind.JsonNode emptyHistory() throws Exception {
        return json.readTree("{}");
    }

    private com.fasterxml.jackson.databind.JsonNode queueWith(String promptId) throws Exception {
        return json.readTree("{\"queue_running\":[[0,\"" + promptId + "\",{}]],\"queue_pending\":[]}");
    }

    private com.fasterxml.jackson.databind.JsonNode emptyQueue() throws Exception {
        return json.readTree("{\"queue_running\":[],\"queue_pending\":[]}");
    }

    @Test
    void stillQueuedIsProcessingNotLost() throws Exception {
        // 测什么：history 空但作业还在队列里 → 仍是 processing
        // 怎么算红：判成 lost —— 每一个排队中的任务都会被重投，GPU 上跑出一堆重复作业，
        //          用户还会看到任务莫名其妙重来
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(emptyHistory());
        when(client.getQueueCached(eq(BASE), anyInt())).thenReturn(queueWith(PROMPT));

        assertEquals(GenerationState.PROCESSING, engine.poll(task()).getState());
    }

    @Test
    void missingFromBothQueueAndHistoryIsLost() throws Exception {
        // 测什么：队列里没有、history 也空 → 判定丢失
        // 怎么算红：仍返回 processing —— 就是线上那个「白等 60 分钟」的行为
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(emptyHistory());
        when(client.getQueueCached(eq(BASE), anyInt())).thenReturn(emptyQueue());

        RemoteStatus status = engine.poll(task());

        assertEquals(GenerationState.LOST, status.getState());
        assertTrue(status.getErrorMsg().contains("gpu-3"), "原因要指名是哪个节点：" + status.getErrorMsg());
    }

    @Test
    void completedBetweenTheTwoChecksIsNotMistakenForLost() throws Exception {
        // 测什么：**本设计的核心竞态**——第一次读 history 时还没写完，读队列时作业已经完成离队，
        //        此时必须靠「读队列之后再读一次 history」兜住，绝不能判丢失
        // 怎么算红：只查队列就下结论 —— 一个已经成功的任务会被判丢失并重投，
        //        用户白等一轮，GPU 白跑一遍，而结果其实早就有了
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt()))
                .thenReturn(emptyHistory())                                  // 第一次：空
                .thenReturn(json.readTree("{\"" + PROMPT + "\":{\"status\":{\"status_str\":\"success\","
                        + "\"completed\":true},\"outputs\":{}}}"));          // 复查：已写入
        when(client.getQueueCached(eq(BASE), anyInt())).thenReturn(emptyQueue());  // 已离队

        assertEquals(GenerationState.PROCESSING, engine.poll(task()).getState());
        verify(client, times(2)).getHistory(eq(BASE), eq(PROMPT), anyInt());
    }

    @Test
    void queueQueryFailureDoesNotConcludeLost() throws Exception {
        // 测什么：队列查询本身失败时不下结论（异常 ≠ 空）
        // 怎么算红：把「查不到队列」当成「不在队列」—— 网络抖一下就会把一批正常任务判丢失重投
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(emptyHistory());
        when(client.getQueueCached(eq(BASE), anyInt())).thenThrow(new RuntimeException("Connection refused"));

        assertEquals(GenerationState.PROCESSING, engine.poll(task()).getState());
    }

    @Test
    void unreachableNodeStillThrowsAsBefore() throws Exception {
        // 测什么：节点整体不可达时行为与改动前一致（抛异常，交给既有超龄兜底）
        // 怎么算红：吞掉异常当成丢失 —— 502 的节点会让所有在途任务立刻重投，
        //          节点恢复后原作业还在跑，等于一份活干两遍
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt()))
                .thenThrow(new RuntimeException("ComfyUI 查询队列失败: 502"));

        assertThrows(RuntimeException.class, () -> engine.poll(task()));
    }

    @Test
    void pendingQueueAlsoCountsAsQueued() throws Exception {
        // 测什么：排队中（queue_pending）和运行中一样算「还在」
        // 怎么算红：只认 queue_running —— 排队中的任务全部被判丢失，队列越长炸得越厉害
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(emptyHistory());
        when(client.getQueueCached(eq(BASE), anyInt())).thenReturn(
                json.readTree("{\"queue_running\":[],\"queue_pending\":[[3,\"" + PROMPT + "\",{}]]}"));

        assertEquals(GenerationState.PROCESSING, engine.poll(task()).getState());
    }
}
