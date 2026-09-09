package org.example.seedancegenarate.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.Impl.ComfyUiEngine;
import org.example.seedancegenarate.engine.comfyui.ComfyUiClient;
import org.example.seedancegenarate.engine.comfyui.ComfyUiFleet;
import org.example.seedancegenarate.engine.comfyui.ComfyUiNodeScheduler;
import org.example.seedancegenarate.engine.comfyui.ComfyUiProperties;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComfyUiSubmissionBoundaryTest {

    private static final String BASE_URL = "http://gpu-1";

    private final ObjectMapper json = new ObjectMapper();
    private ComfyUiProperties properties;
    private ComfyUiClient client;
    private ComfyUiNodeScheduler scheduler;
    private WorkflowBuilder builder;
    private ComfyUiProperties.Node node;
    private ComfyUiNodeScheduler.NodeSelection selection;
    private ComfyUiEngine engine;

    @BeforeEach
    void setUp() {
        properties = new ComfyUiProperties();
        client = mock(ComfyUiClient.class);
        scheduler = mock(ComfyUiNodeScheduler.class);
        builder = mock(WorkflowBuilder.class);

        node = new ComfyUiProperties.Node();
        node.setId("gpu-1");
        node.setBaseUrl(BASE_URL);
        node.setEnabled(true);
        selection = new ComfyUiNodeScheduler.NodeSelection(node, 41L);

        when(builder.model()).thenReturn("t2v");
        when(builder.spec()).thenReturn(textSpec());
        when(scheduler.pick(eq("t2v"), isNull())).thenReturn(selection);
        engine = new ComfyUiEngine(properties, client, scheduler, mock(ComfyUiFleet.class),
                List.of(builder), json, new VideoCompletionProperties());
    }

    @Test
    void allNodesUnavailableIsASafeRetrySignal() {
        // 【测什么】调度器明确选不出节点时，ComfyUI 适配器返回“尚未接单”的 typed 信号。
        // 【怎么算红】若仍抛普通 RuntimeException，GenerationAttemptService 会把它停成 UNKNOWN 而不能退避重试。
        RuntimeException unavailable = new RuntimeException("全部节点不可用");
        when(scheduler.pick(eq("t2v"), isNull())).thenThrow(unavailable);

        SubmissionNotAcceptedException thrown = assertThrows(SubmissionNotAcceptedException.class,
                () -> engine.submit(command()));

        assertSame(unavailable, thrown.getCause());
        verifyNoInteractions(client);
        verify(scheduler, never()).releaseDispatch(any());
    }

    @Test
    void localValidationFailureIsASafeRetrySignal() {
        // 【测什么】模型参数本地校验失败发生在选节点和 HTTP 之前，必须明确标记为未接单。
        // 【怎么算红】若校验异常未包装，attempt 会被永久停放；若继续调度，说明提交边界提前越过了。
        when(builder.spec()).thenReturn(imageSpec());

        assertThrows(SubmissionNotAcceptedException.class, () -> engine.submit(command()));

        verify(scheduler, never()).pick(anyString(), any());
        verifyNoInteractions(client);
    }

    @Test
    void referenceUploadFailureBeforePromptIsASafeRetrySignal() throws Exception {
        // 【测什么】参考素材下载/上传失败时 /prompt 尚未调用，typed 信号允许同一 attempt 安全重试。
        // 【怎么算红】若异常原样逸出会变 UNKNOWN；若 submitPrompt 被调用则说明失败后仍向供应商提交。
        when(builder.spec()).thenReturn(imageSpec());
        RuntimeException downloadFailed = new RuntimeException("素材下载失败");
        when(client.downloadBytes("https://example.invalid/ref.png")).thenThrow(downloadFailed);

        SubmissionNotAcceptedException thrown = assertThrows(SubmissionNotAcceptedException.class,
                () -> engine.submit(GenerateCommand.builder()
                        .model("t2v")
                        .prompt("prompt")
                        .imageUrls(List.of("https://example.invalid/ref.png"))
                        .build()));

        assertSame(downloadFailed, thrown.getCause());
        verify(client, never()).submitPrompt(anyString(), any(), anyString(), any(), anyInt());
        verify(scheduler).releaseDispatch(selection);
    }

    @Test
    void workflowBuildFailureBeforePromptIsASafeRetrySignal() throws Exception {
        // 【测什么】素材准备完成但工作流尚未构建成功，也仍位于 /prompt 之前的安全失败区。
        // 【怎么算红】若异常不是 SubmissionNotAcceptedException 或仍调用 submitPrompt，本测试必须失败。
        IllegalStateException buildFailed = new IllegalStateException("模板缺少节点");
        when(builder.build(any(), any())).thenThrow(buildFailed);

        SubmissionNotAcceptedException thrown = assertThrows(SubmissionNotAcceptedException.class,
                () -> engine.submit(command()));

        assertSame(buildFailed, thrown.getCause());
        verify(client, never()).submitPrompt(anyString(), any(), anyString(), any(), anyInt());
        verify(scheduler).releaseDispatch(selection);
    }

    @Test
    void submitPromptFailureRemainsUnknownSignal() throws Exception {
        // 【测什么】一旦进入 submitPrompt，超时必须原样上抛，让 attempt 进入 UNKNOWN/RECOVERY_REQUIRED。
        // 【怎么算红】若超时被包装成 SubmissionNotAcceptedException，Worker 会盲目重投并可能生成双份任务。
        when(builder.build(any(), any())).thenReturn(json.readTree("{}"));
        SocketTimeoutException timeout = new SocketTimeoutException("read timed out");
        when(client.submitPrompt(eq(BASE_URL), any(), anyString(), isNull(), anyInt())).thenThrow(timeout);

        SocketTimeoutException thrown = assertThrows(SocketTimeoutException.class,
                () -> engine.submit(command()));

        assertSame(timeout, thrown);
        verify(scheduler).releaseDispatch(selection);
    }

    @Test
    void stableRequestIdAndSelectedNodeAreRecordedBeforePrompt() throws Exception {
        // 【测什么】实际节点必须先交给 attempt 持久化，然后 /prompt 才使用稳定 providerRequestId 提交。
        // 【怎么算红】恢复随机 clientId，或把 observer 放到 submitPrompt 后，本测试必须失败。
        when(builder.build(any(), any())).thenReturn(json.readTree("{}"));
        AtomicReference<String> persistedNode = new AtomicReference<>();
        when(client.submitPrompt(eq(BASE_URL), any(), eq("request-stable-1"), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    assertEquals("gpu-1", persistedNode.get());
                    return "prompt-1";
                });

        SubmitResult result = engine.submit(GenerateCommand.builder()
                .model("t2v")
                .prompt("prompt")
                .providerRequestId("request-stable-1")
                .build(), persistedNode::set);

        assertEquals("prompt-1", result.getProviderTaskId());
        assertEquals("gpu-1", result.getNodeId());
        verify(client).submitPrompt(BASE_URL, json.readTree("{}"),
                "request-stable-1", null, properties.getReadTimeoutMs());
    }

    private GenerateCommand command() {
        return GenerateCommand.builder().model("t2v").prompt("prompt").build();
    }

    private ModelSpec textSpec() {
        return new ModelSpec("comfyui", "t2v", "文生视频", false,
                0, 0, List.of(), 1, 10, List.of());
    }

    private ModelSpec imageSpec() {
        return new ModelSpec("comfyui", "t2v", "图生视频", true,
                1, 1, List.of(), 1, 10, List.of());
    }
}
