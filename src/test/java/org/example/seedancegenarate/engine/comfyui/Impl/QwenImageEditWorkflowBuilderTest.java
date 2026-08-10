package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试（不加载 Spring 上下文 / 不需要 DB），验证 Qwen-Image-Edit 图生图工作流的注入与多参考图重建。
 */
class QwenImageEditWorkflowBuilderTest {

    private final QwenImageEditWorkflowBuilder builder =
            new QwenImageEditWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptSeedResolutionAndRebuildsImages() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_IMAGE)
                .prompt("图一的人物换上图二的衣服")
                .ratio("16:9")
                .model("qwen-image-edit")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of("a.png", "b.jpg"), List.of(), List.of()));

        // 提示词注入到 QwenImageEditPlus（正向）
        assertEquals("图一的人物换上图二的衣服", wf.path("48").path("inputs").path("prompt").asText());
        // 种子已随机（不是模板默认值）
        assertNotEquals(894758404622672L, wf.path("42").path("inputs").path("seed").asLong());
        // 比例 → 输出画布分辨率
        assertEquals(1344, wf.path("49").path("inputs").path("width").asInt());
        assertEquals(768, wf.path("49").path("inputs").path("height").asInt());

        // 模板单张占位（LoadImage / Scale / 悬空 VAEEncode）被删除
        assertTrue(wf.path("46").isMissingNode());
        assertTrue(wf.path("39").isMissingNode());
        assertTrue(wf.path("52").isMissingNode());

        // 按 2 张图重建 LoadImage → 缩放
        assertEquals("a.png", wf.path("qie_load_0").path("inputs").path("image").asText());
        assertEquals("b.jpg", wf.path("qie_load_1").path("inputs").path("image").asText());
        assertEquals("LoadImage", wf.path("qie_load_0").path("class_type").asText());
        assertEquals("ImageScaleToTotalPixels", wf.path("qie_scale_0").path("class_type").asText());
        assertEquals("qie_load_0", wf.path("qie_scale_0").path("inputs").path("image").get(0).asText());

        // image1 / image2 接到缩放节点，且没有第 3 个
        JsonNode in48 = wf.path("48").path("inputs");
        assertEquals("qie_scale_0", in48.path("image1").get(0).asText());
        assertEquals(0, in48.path("image1").get(1).asInt());
        assertEquals("qie_scale_1", in48.path("image2").get(0).asText());
        assertTrue(in48.path("image3").isMissingNode());

        // 输出前缀已清理
        assertEquals("image/qwen-image-edit", wf.path("36").path("inputs").path("filename_prefix").asText());
    }

    @Test
    void clampsToThreeImages() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .prompt("合成")
                .ratio("1:1")
                .model("qwen-image-edit")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of("1.png", "2.png", "3.png", "4.png"), List.of(), List.of()));

        JsonNode in48 = wf.path("48").path("inputs");
        assertFalse(in48.path("image3").isMissingNode());
        assertTrue(in48.path("image4").isMissingNode());
        assertFalse(wf.path("qie_load_2").isMissingNode());
        assertTrue(wf.path("qie_load_3").isMissingNode());
    }

    @Test
    void specExposesImageEditConstraints() {
        var spec = builder.spec();
        assertEquals("qwen-image-edit", spec.model());
        assertEquals("comfyui", spec.provider());
        assertTrue(spec.needImages());
        assertEquals(1, spec.imageMin());
        assertEquals(3, spec.imageMax());
        assertEquals(OutputType.IMAGE, spec.outputType());
        assertTrue(spec.durations().isEmpty());
        assertTrue(spec.ratios().contains("16:9"));
    }
}
