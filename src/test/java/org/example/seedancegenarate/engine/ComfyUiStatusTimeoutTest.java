package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.Impl.ComfyUiEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态查询的超时口径守卫。
 * <p>
 * 背景：{@code readTimeoutMs=60000} 原本同时喂给「传几十 MB 素材」和「读几 KB JSON」两类调用。
 * Hutool 的 {@code timeout()} 同时设 connect 和 read，所以一台 SYN 无人应答的机器
 * 会让对账里的单条任务阻塞满 60 秒——两条就够把 120 秒的对账锁租约跑爆。
 */
class ComfyUiStatusTimeoutTest {

    private static final String PROMPT = "436120e1-8199-4a16-b98a-0bb11f8186d8";
    private static final String BASE = "http://node/gpu-3";
    private static final int STATUS_TIMEOUT = 5000;
    private static final int TRANSFER_TIMEOUT = 60000;

    private ComfyUiClient client;
    private ComfyUiProperties props;
    private WorkflowBuilder builder;
    private ComfyUiNodeScheduler scheduler;
    private ComfyUiEngine engine;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = mock(ComfyUiClient.class);
        scheduler = mock(ComfyUiNodeScheduler.class);
        props = new ComfyUiProperties();
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId("gpu-3");
        node.setBaseUrl(BASE);
        node.setEnabled(true);
        props.setNodes(List.of(node));

        builder = mock(WorkflowBuilder.class);
        when(builder.model()).thenReturn("t2v");
        // 纯文生：不需要参考图，submit 不会走 uploadRefs，直达 submitPrompt
        when(builder.spec()).thenReturn(new ModelSpec("comfyui", "t2v", "文生视频",
                false, 0, 0, List.of(), 1, 10, List.of()));

        engine = new ComfyUiEngine(props, client, scheduler, new org.example.seedancegenarate.engine.comfyui.ComfyUiFleet(props), List.of(builder),
                json, new VideoCompletionProperties());
    }

    private VideoTask task() {
        VideoTask t = new VideoTask();
        t.setId(1180L);
        t.setBizTaskId("tsk_bb3f99fb");
        t.setNodeId("gpu-3");
        t.setProviderTaskId(PROMPT);
        t.setStatus("PROCESSING");
        t.setModel("t2v");
        return t;
    }

    @Test
    void pollUsesStatusTimeoutNotTransferTimeout() throws Exception {
        // 【测什么】poll 查 /history 用的是状态超时（5s），不是传素材用的 60s
        // 【怎么算红】仍然传 60000 —— 一台 hang 住的节点能让对账的每条任务阻塞满一分钟，
        //            两条就把 120 秒的锁租约跑爆，另一个实例并发进来，且分支 ③④ 这轮不执行
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt()))
                .thenReturn(json.readTree("{\"" + PROMPT + "\":{\"status\":{\"status_str\":\"success\","
                        + "\"completed\":true},\"outputs\":{}}}"));

        engine.poll(task());

        verify(client).getHistory(eq(BASE), eq(PROMPT), eq(STATUS_TIMEOUT));
        verify(client, never()).getHistory(eq(BASE), eq(PROMPT), eq(TRANSFER_TIMEOUT));
    }

    @Test
    void lostProbeUsesStatusTimeoutForBothQueueAndRecheck() throws Exception {
        // 【测什么】丢失判定的三次调用（history → queue → history）全都走状态超时
        // 【怎么算红】其中任何一次仍传 60000 —— 今天新加的丢失判定把 poll 的调用数从 1 次
        //            变成 3 次，正好把「单条最坏 60 秒」放大成「单条最坏 180 秒」
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(json.readTree("{}"));
        when(client.getQueueCached(eq(BASE), anyInt()))
                .thenReturn(json.readTree("{\"queue_running\":[],\"queue_pending\":[]}"));

        assertEquals(GenerationState.LOST, engine.poll(task()).getState());

        verify(client, org.mockito.Mockito.times(2)).getHistory(eq(BASE), eq(PROMPT), eq(STATUS_TIMEOUT));
        verify(client).getQueueCached(eq(BASE), eq(STATUS_TIMEOUT));
    }

    @Test
    void submitStillUsesTransferTimeout() throws Exception {
        // 【测什么】提交仍然用 60s —— 这次改动不许把大载荷路径也一起收紧
        // 【怎么算红】提交也用 5s —— 上传几十 MB 参考素材必然超时，所有图生视频任务当场失败
        ComfyUiProperties.Node node = props.getNodes().get(0);
        // 提交路径调的是 pick(model, pinnedNodeId)：model 用来做能力/显存过滤，
        // pinnedNodeId 是管理员指定节点（这里为 null，走正常调度）
        when(scheduler.pick(eq("t2v"), isNull()))
                .thenReturn(new ComfyUiNodeScheduler.NodeSelection(node, 1L));
        when(builder.build(any(), any())).thenReturn(json.readTree("{}"));
        when(client.submitPrompt(anyString(), any(), anyString(), isNull(), anyInt()))
                .thenReturn(PROMPT);

        engine.submit(GenerateCommand.builder().model("t2v").prompt("hi").build());

        verify(client).submitPrompt(eq(BASE), any(), anyString(), isNull(), eq(TRANSFER_TIMEOUT));
    }

    @Test
    void zeroStatusTimeoutIsClampedToOneSecond() {
        // 【测什么】把 status-timeout-ms 配成 0 时被夹到 1000ms
        // 【怎么算红】原样返回 0 —— Java 的 setConnectTimeout(0) 是**无限等待**，
        //            配错一个 0 比根本没做这次改动还糟（原来至少还有 60 秒上限）
        props.setStatusTimeoutMs(0);
        assertEquals(1000, props.getStatusTimeoutMs());

        props.setStatusTimeoutMs(-1);
        assertEquals(1000, props.getStatusTimeoutMs());

        props.setStatusTimeoutMs(8000);
        assertEquals(8000, props.getStatusTimeoutMs(), "正常配置不该被改动");
    }
}
