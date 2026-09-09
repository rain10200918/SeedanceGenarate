package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.Impl.ComfyUiEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiFleet;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComfyUiSubmissionRecoveryTest {

    @Test
    void stableClientIdFindsPromptOnTheOriginalNode() throws Exception {
        // 【测什么】UNKNOWN attempt 能在原节点按稳定 clientId 找回 promptId，而不是再次提交。
        // 【怎么算红】去掉 ComfyUiEngine.findSubmission 的节点亲和或 clientId 查询，本测试必须失败。
        Fixture f = fixture();
        when(f.client.findPromptIdByClientId("http://gpu-5", "request-1", 5_000))
                .thenReturn("prompt-1769");

        SubmitResult found = f.engine.findSubmission("request-1", "gpu-5");

        assertEquals("prompt-1769", found.getProviderTaskId());
        assertEquals("gpu-5", found.getNodeId());
        verify(f.client).findPromptIdByClientId("http://gpu-5", "request-1", 5_000);
    }

    @Test
    void reachableNodeWithoutTraceDoesNotInventAResubmission() throws Exception {
        // 【测什么】节点可查但 queue/history 没有稳定 clientId 时只返回“未找到”，不创建新任务。
        // 【怎么算红】若查询为空时生成假的 promptId 或调用 submit，null 断言/交互验证必须失败。
        Fixture f = fixture();
        when(f.client.findPromptIdByClientId("http://gpu-5", "request-1", 5_000))
                .thenReturn(null);

        assertNull(f.engine.findSubmission("request-1", "gpu-5"));
    }

    private Fixture fixture() {
        ComfyUiProperties properties = new ComfyUiProperties();
        properties.setStatusTimeoutMs(5_000);
        ComfyUiProperties.Node node = new ComfyUiProperties.Node();
        node.setId("gpu-5");
        node.setBaseUrl("http://gpu-5");
        node.setEnabled(true);
        properties.setNodes(List.of(node));
        ComfyUiClient client = mock(ComfyUiClient.class);
        ComfyUiEngine engine = new ComfyUiEngine(properties, client,
                mock(ComfyUiNodeScheduler.class), new ComfyUiFleet(properties), List.of(),
                new ObjectMapper(), new VideoCompletionProperties());
        return new Fixture(client, engine);
    }

    private record Fixture(ComfyUiClient client, ComfyUiEngine engine) {
    }
}
