package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试（不加载 Spring 上下文 / 不需要 DB），验证工作流图的注入与参考图重建逻辑。
 */
class MiniMaxH3WorkflowBuilderTest {

    private final MiniMaxH3WorkflowBuilder builder = new MiniMaxH3WorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptRatioDurationAndRebuildsRefImages() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("你好世界")
                .duration(30)          // 超范围 → 夹取到 15
                .ratio("16:9")
                .model("minimax-h3")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of("f0.png", "f1.jpg"), List.of(), List.of()));

        // 提示词注入
        assertEquals("你好世界", wf.path("170").path("inputs").path("value").asText());
        // 时长夹取、比例映射为完整标签
        assertEquals(15, wf.path("161").path("inputs").path("duration").asInt());
        assertEquals("16:9 (Widescreen)", wf.path("161").path("inputs").path("aspect_ratio").asText());
        // 种子已随机（不是模板默认值）
        assertNotEquals(816814387792933L, wf.path("131").path("inputs").path("noise_seed").asLong());

        // 模板里的参考图 LoadImage 节点被删除
        assertTrue(wf.path("114").isMissingNode());
        assertTrue(wf.path("169").isMissingNode());
        assertTrue(wf.path("176").isMissingNode());
        // UI-only 节点被剥离
        assertTrue(wf.path("159").isMissingNode());
        assertTrue(wf.path("164").isMissingNode());
        assertTrue(wf.path("165").isMissingNode());

        // 按 2 张图重建 LoadImage 节点
        assertEquals("f0.png", wf.path("img_0").path("inputs").path("image").asText());
        assertEquals("f1.jpg", wf.path("img_1").path("inputs").path("image").asText());
        assertEquals("LoadImage", wf.path("img_0").path("class_type").asText());

        // 节点 167 的 ref 连线重连到这 2 张，且没有第 3 条
        JsonNode in167 = wf.path("167").path("inputs");
        assertEquals("img_0", in167.path("ref_images.ref_image_0").get(0).asText());
        assertEquals(0, in167.path("ref_images.ref_image_0").get(1).asInt());
        assertEquals("img_1", in167.path("ref_images.ref_image_1").get(0).asText());
        assertTrue(in167.path("ref_images.ref_image_2").isMissingNode());
        // 非 ref 的输入保持不变
        assertEquals("match", in167.path("ref_image_size").asText());
    }

    @Test
    void specExposesConstraints() {
        ModelSpecAssertions(builder);
    }

    private static void ModelSpecAssertions(MiniMaxH3WorkflowBuilder builder) {
        var spec = builder.spec();
        assertEquals("minimax-h3", spec.model());
        assertEquals("comfyui", spec.provider());
        assertTrue(spec.needImages());
        assertEquals(1, spec.imageMin());
        assertEquals(9, spec.imageMax());
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
        assertTrue(spec.ratios().contains("21:9"));
    }
}
