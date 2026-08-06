package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试（不加载 Spring 上下文 / 不需要 DB），验证文生视频工作流图的注入与 UI 节点剥离逻辑。
 */
class MiniMaxH3TextToVideoWorkflowBuilderTest {

    private final MiniMaxH3TextToVideoWorkflowBuilder builder =
            new MiniMaxH3TextToVideoWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptRatioDurationAndStripsUiNodes() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_VIDEO)
                .prompt("你好世界")
                .duration(30)          // 超范围 → 夹取到 15
                .ratio("16:9")
                .model("minimax-h3-t2v")
                .build();

        JsonNode wf = builder.build(cmd, List.of());

        // 提示词注入
        assertEquals("你好世界", wf.path("187").path("inputs").path("value").asText());
        // 时长夹取、比例映射为完整标签
        assertEquals(15, wf.path("185").path("inputs").path("duration").asInt());
        assertEquals("16:9 (Widescreen)", wf.path("185").path("inputs").path("aspect_ratio").asText());
        // 种子已随机（不是模板默认值）
        assertNotEquals(450120951266578L, wf.path("173").path("inputs").path("noise_seed").asLong());

        // UI-only 节点被剥离
        assertTrue(wf.path("192").isMissingNode());
        assertTrue(wf.path("196").isMissingNode());

        // 核心生成 / 输出节点保留，输出前缀已清理
        assertFalse(wf.path("194").isMissingNode());
        assertEquals("MiniMaxH3ImageToVideo", wf.path("194").path("class_type").asText());
        assertEquals("video/minimax-h3-t2v", wf.path("193").path("inputs").path("filename_prefix").asText());
    }

    @Test
    void specExposesConstraints() {
        var spec = builder.spec();
        assertEquals("minimax-h3-t2v", spec.model());
        assertEquals("comfyui", spec.provider());
        assertFalse(spec.needImages());
        assertEquals(0, spec.imageMin());
        assertEquals(0, spec.imageMax());
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
        assertTrue(spec.ratios().contains("16:9"));
    }
}
