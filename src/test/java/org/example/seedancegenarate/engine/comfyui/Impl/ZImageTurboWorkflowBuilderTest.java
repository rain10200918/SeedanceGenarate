package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试（不加载 Spring 上下文 / 不需要 DB），验证文生图工作流图的注入逻辑。
 */
class ZImageTurboWorkflowBuilderTest {

    private final ZImageTurboWorkflowBuilder builder =
            new ZImageTurboWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptSeedAndResolution() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_IMAGE)   // 引擎不读该字段，图片同样走文本提交路径
                .prompt("白发中年人，穿着道袍")
                .ratio("16:9")
                .model("z-image-turbo")
                .build();

        JsonNode wf = builder.build(cmd, List.of());

        // 提示词注入到 CLIPTextEncode
        assertEquals("白发中年人，穿着道袍", wf.path("57:27").path("inputs").path("text").asText());
        // 比例 → 分辨率
        assertEquals(1344, wf.path("57:13").path("inputs").path("width").asInt());
        assertEquals(768, wf.path("57:13").path("inputs").path("height").asInt());
        // 种子已随机（不是模板默认值）
        assertNotEquals(816146065605252L, wf.path("57:3").path("inputs").path("seed").asLong());

        // 输出节点为 SaveImage，前缀已清理
        assertEquals("SaveImage", wf.path("9").path("class_type").asText());
        assertEquals("image/z-image-turbo", wf.path("9").path("inputs").path("filename_prefix").asText());
    }

    @Test
    void buildFallsBackToSquareForUnknownRatio() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .prompt("a cat")
                .ratio("bogus")
                .model("z-image-turbo")
                .build();

        JsonNode wf = builder.build(cmd, List.of());

        assertEquals(1024, wf.path("57:13").path("inputs").path("width").asInt());
        assertEquals(1024, wf.path("57:13").path("inputs").path("height").asInt());
    }

    @Test
    void specExposesImageConstraints() {
        var spec = builder.spec();
        assertEquals("z-image-turbo", spec.model());
        assertEquals("comfyui", spec.provider());
        assertFalse(spec.needImages());
        assertEquals(0, spec.imageMin());
        assertEquals(0, spec.imageMax());
        assertEquals(OutputType.IMAGE, spec.outputType());
        assertTrue(spec.durations().isEmpty());
        assertTrue(spec.ratios().contains("1:1"));
        assertTrue(spec.ratios().contains("16:9"));
    }

    /** 回归（2026-08-06 实测）：API 不传 ratio 时 Map.of.get(null) 会 NPE——空值必须归一为 1:1 默认 */
    @Test
    void buildWithNullRatioDoesNotNpe() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_IMAGE)
                .prompt("测试")
                .model("z-image-turbo")
                // 不传 ratio / duration
                .build();

        JsonNode wf = builder.build(cmd, List.of());

        // 归一为共享层默认 16:9（与 VideoSubmitService 的默认一致），不抛 NPE
        assertEquals(1344, wf.path("57:13").path("inputs").path("width").asInt());
        assertEquals(768, wf.path("57:13").path("inputs").path("height").asInt());
    }
}
