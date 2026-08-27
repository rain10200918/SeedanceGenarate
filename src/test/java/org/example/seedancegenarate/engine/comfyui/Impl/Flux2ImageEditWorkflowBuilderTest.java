package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Flux2ImageEditWorkflowBuilderTest {

    private final Flux2ImageEditWorkflowBuilder builder =
            new Flux2ImageEditWorkflowBuilder(new ObjectMapper());

    @Test
    void specMatchesExpectations() {
        ModelSpec spec = builder.spec();
        assertEquals("comfyui", spec.provider());
        assertEquals("flux2-image-edit", spec.model());
        assertEquals("Flux 2.0 图像编辑", spec.label());
        assertTrue(spec.needImages());
        assertEquals(1, spec.imageMin());
        assertEquals(2, spec.imageMax());
        assertEquals(OutputType.IMAGE, spec.outputType());
        assertTrue(spec.ratios().contains("16:9"));
        assertTrue(spec.ratios().contains("2:3"));
        assertNotNull(spec.megapixels());
        assertTrue(spec.megapixels().contains(1.0));
    }

    @Test
    void buildSingleImageWorkflow() throws Exception {
        GenerateCommand command = GenerateCommand.builder()
                .model("flux2-image-edit")
                .prompt("将图片转为手绘插画风")
                .megapixels(1.2)
                .build();
        ReferenceFiles files = new ReferenceFiles(List.of("input_single.png"), null, null);

        JsonNode root = builder.build(command, files);

        // 验证单图输出节点 27 存在
        assertNotNull(root.get("27"), "SaveImage 节点 27 必须存在");
        assertEquals("SaveImage", root.get("27").get("class_type").asText());

        // 验证单图参考图节点 45
        assertEquals("input_single.png", root.get("45").get("inputs").get("image").asText());

        // 验证单图提示词节点 37
        assertEquals("将图片转为手绘插画风", root.get("37").get("inputs").get("value").asText());

        // 验证单图缩放节点 51
        assertEquals(1.2, root.get("51").get("inputs").get("megapixels").asDouble());

        // 验证单图种子节点 39 已注入动态种子
        assertTrue(root.get("39").get("inputs").get("seed").asLong() > 0);

        // 验证双图特有节点不在单图工作流中
        assertNull(root.get("2"), "双图 SaveImage 节点 2 不应出现在单图工作流中");
        assertNull(root.get("21"), "双图 LoadImage 节点 21 不应出现在单图工作流中");
    }

    @Test
    void buildDualImageWorkflow() throws Exception {
        GenerateCommand command = GenerateCommand.builder()
                .model("flux2-image-edit")
                .prompt("将图2人物头部替换到图1")
                .ratio("16:9")
                .megapixels(1.5)
                .build();
        ReferenceFiles files = new ReferenceFiles(List.of("person_a.png", "person_b.png"), null, null);

        JsonNode root = builder.build(command, files);

        // 验证双图输出节点 2 存在
        assertNotNull(root.get("2"), "SaveImage 节点 2 必须存在");
        assertEquals("SaveImage", root.get("2").get("class_type").asText());

        // 验证双图参考图 A (21) 与 B (9)
        assertEquals("person_a.png", root.get("21").get("inputs").get("image").asText());
        assertEquals("person_b.png", root.get("9").get("inputs").get("image").asText());

        // 验证双图提示词节点 20
        assertEquals("将图2人物头部替换到图1", root.get("20").get("inputs").get("value").asText());

        // 验证双图分辨率选择器节点 19
        assertEquals("16:9 (Widescreen)", root.get("19").get("inputs").get("aspect_ratio").asText());
        assertEquals(1.5, root.get("19").get("inputs").get("megapixels").asDouble());

        // 验证双图种子节点 13 已注入动态种子
        assertTrue(root.get("13").get("inputs").get("seed").asLong() > 0);

        // 验证单图特有节点不在双图工作流中
        assertNull(root.get("27"), "单图 SaveImage 节点 27 不应出现在双图工作流中");
        assertNull(root.get("45"), "单图 LoadImage 节点 45 不应出现在双图工作流中");
    }

    @Test
    void buildThrowsWhenNoImages() {
        GenerateCommand command = GenerateCommand.builder()
                .model("flux2-image-edit")
                .prompt("test")
                .build();
        ReferenceFiles emptyFiles = new ReferenceFiles(List.of(), null, null);

        assertThrows(IllegalArgumentException.class, () -> builder.build(command, emptyFiles));
    }
}
