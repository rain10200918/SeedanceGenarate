package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComfyUiClientTraceParsingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void findsStableClientIdInRunningAndPendingQueueShape() throws Exception {
        // 【测什么】按 ComfyUI 原生队列元组下标 3 的 extra_data.client_id 反查 promptId。
        // 【怎么算红】误读工作流下标 2 或只扫描 running，pending 断言必须失败。
        var queue = json.readTree("{\"queue_running\":[],\"queue_pending\":["
                + "[9,\"prompt-1769\",{}, {\"client_id\":\"request-1\"}, []]]}");

        assertEquals("prompt-1769", ComfyUiClient.findInQueue(queue, "request-1"));
        assertNull(ComfyUiClient.findInQueue(queue, "other"));
    }

    @Test
    void findsStableClientIdInHistoryPromptTuple() throws Exception {
        // 【测什么】完成后的 history 通过 entry.prompt[3].client_id 找回 map key promptId。
        // 【怎么算红】只查 queue 或误把 history 顶层当数组，本测试必须失败。
        var history = json.readTree("{\"prompt-finished\":{\"prompt\":"
                + "[9,\"prompt-finished\",{}, {\"client_id\":\"request-1\"}, []],\"outputs\":{}}}");

        assertEquals("prompt-finished", ComfyUiClient.findInHistory(history, "request-1"));
        assertNull(ComfyUiClient.findInHistory(history, "other"));
    }
}
