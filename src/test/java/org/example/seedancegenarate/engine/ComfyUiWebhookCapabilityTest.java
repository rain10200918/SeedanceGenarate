package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.Impl.ComfyUiEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.entity.VideoTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ComfyUI 的能力声明必须与事实一致。
 * <p>
 * 2026-08-26 在生产机 {@code /mnt/nvme0n1/Model/ComfyUI} 上确认：{@code server.py}、
 * {@code api_server/}、{@code middleware/} 对 webhook <b>零命中</b>——原生 ComfyUI 的
 * {@code /prompt} 静默丢弃多余顶层字段，完成事件走 WebSocket。而代码声明自己是 CALLBACK，
 * 于是任务被踢出 2 秒轮询器、只剩 30 秒对账 + 60 秒退避，<b>每个任务平均多等 45 秒</b>；
 * 同时还把回调 token（线上与 COMFYUI_ACCESS_TOKEN 同值）发给了一个会丢弃它的服务。
 */
class ComfyUiWebhookCapabilityTest {

    private static final String PROMPT = "436120e1-8199";
    private static final String BASE = "http://node/gpu-3";

    private ComfyUiClient client;
    private ComfyUiProperties props;
    private VideoCompletionProperties completion;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = mock(ComfyUiClient.class);
        props = new ComfyUiProperties();
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId("gpu-3");
        node.setBaseUrl(BASE);
        node.setEnabled(true);
        props.setNodes(List.of(node));

        completion = new VideoCompletionProperties();
        completion.setCallbackBaseUrl("http://192.168.10.33:8080");
        completion.setCallbackSecret("s3cret");
    }

    private ComfyUiEngine engine() {
        return new ComfyUiEngine(props, client, mock(ComfyUiNodeScheduler.class), new org.example.seedancegenarate.engine.comfyui.ComfyUiFleet(props),
                List.of(), json, completion);
    }

    private VideoTask task() {
        VideoTask t = new VideoTask();
        t.setId(1L);
        t.setBizTaskId("tsk_x");
        t.setNodeId("gpu-3");
        t.setProviderTaskId(PROMPT);
        t.setStatus("PROCESSING");
        return t;
    }

    @Test
    void withoutWebhookSupportEngineDeclaresItselfPollBased() {
        // 【测什么】默认（不支持回调）时声明成 POLL 且需要轮询 —— 任务才会进 2 秒轮询器
        // 【怎么算红】仍声明 CALLBACK/不需要轮询 —— 就是线上现状：任务被踢出轮询器，
        //            只剩 30 秒对账 + 60 秒退避，每个任务生成完平均白等 45 秒
        props.setWebhookSupported(false);

        assertEquals(CompletionMechanism.POLL, engine().completionMechanism());
        assertTrue(engine().needsPolling(), "不会回调就必须靠轮询推进，否则任务没人管");
    }

    @Test
    void withWebhookSupportEngineIsEventDriven() {
        // 【测什么】置 true 时行为完整回到今天（事件驱动 + 对账兜底）
        // 【怎么算红】开关只有单向效果 —— 将来装了 JobNotify 也切不回事件驱动，
        //            白白多跑一堆轮询
        props.setWebhookSupported(true);

        assertEquals(CompletionMechanism.CALLBACK, engine().completionMechanism());
        assertFalse(engine().needsPolling(), "配了回调基址与密钥就不该再轮询");
    }

    @Test
    void missingCallbackConfigStillForcesPollingEvenIfWebhookSupported() {
        // 【测什么】即便声明支持回调，回调基址/密钥没配也必须回退轮询
        // 【怎么算红】只看 webhookSupported —— 开发环境没配回调地址，任务既等不到回调
        //            也没人轮询，永久卡在 PROCESSING
        props.setWebhookSupported(true);
        completion.setCallbackSecret("");

        assertTrue(engine().needsPolling());
    }

    @Test
    void callbackNeverConcludesLostBecauseHistoryLagsTheEvent() throws Exception {
        // 【测什么】**本次最危险的一条**：ComfyUI 先发 execution_success、后写 history。
        //          回调到达时 history 还空、作业已离队 —— 绝不能因此判丢失
        // 【怎么算红】handleCallback 直接返回 poll 的结果 —— 一个**刚刚成功**的任务被判丢失
        //          并重投，GPU 白跑一遍，用户白等一轮，而结果其实早就有了
        props.setWebhookSupported(true);
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(json.readTree("{}"));
        when(client.getQueueCached(eq(BASE), anyInt()))
                .thenReturn(json.readTree("{\"queue_running\":[],\"queue_pending\":[]}"));

        ComfyUiEngine engine = engine();
        // 同样的输入，直接 poll 会判丢失
        assertEquals(GenerationState.LOST, engine.poll(task()).getState());
        // 但走回调入口必须是 processing
        assertEquals(GenerationState.PROCESSING,
                engine.handleCallback(task(), "{\"data\":{\"prompt_id\":\"" + PROMPT + "\"}}").getState());
    }

    @Test
    void callbackStillPassesThroughRealTerminalStates() throws Exception {
        // 【测什么】绕过丢失判定不能顺手把真正的终态也吃掉
        // 【怎么算红】回调一律返回 processing —— 回调形同虚设，全靠轮询兜底，
        //            等于这次改动白做
        props.setWebhookSupported(true);
        when(client.getHistory(eq(BASE), eq(PROMPT), anyInt())).thenReturn(json.readTree(
                "{\"" + PROMPT + "\":{\"status\":{\"status_str\":\"error\",\"completed\":false}}}"));

        assertEquals(GenerationState.FAILED,
                engine().handleCallback(task(), "{}").getState());
    }
}
