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
 * 纯单元测试：验证 MiniMax-H3 官方加速工作流的注入（含 megapixels 分辨率档位）与参考图重建。
 */
class MiniMaxH3AccelWorkflowBuilderTest {

    private final MiniMaxH3AccelWorkflowBuilder builder =
            new MiniMaxH3AccelWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptResolutionDurationAndRebuildsRefs() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("你好世界")
                .duration(30)          // 超范围 → 夹取到 15
                .ratio("16:9")
                .megapixels(1.0)
                .model("minimax-h3-accel")
                .build();

        JsonNode wf = builder.build(cmd, List.of("f0.png", "f1.jpg"));

        assertEquals("你好世界", wf.path("138").path("inputs").path("value").asText());
        assertEquals("16:9 (Widescreen)", wf.path("115").path("inputs").path("aspect_ratio").asText());
        assertEquals(1.0, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
        assertEquals(15, wf.path("132").path("inputs").path("value").asInt());
        assertNotEquals(4645316171474L, wf.path("129").path("inputs").path("noise_seed").asLong());

        // 模板参考图占位被删除
        assertTrue(wf.path("137").isMissingNode());
        assertTrue(wf.path("139").isMissingNode());
        assertTrue(wf.path("143").isMissingNode());

        // 按 2 张图重建
        assertEquals("f0.png", wf.path("img_0").path("inputs").path("image").asText());
        assertEquals("f1.jpg", wf.path("img_1").path("inputs").path("image").asText());
        JsonNode in136 = wf.path("136").path("inputs");
        assertEquals("img_0", in136.path("ref_images.ref_image_0").get(0).asText());
        assertEquals("img_1", in136.path("ref_images.ref_image_1").get(0).asText());
        assertTrue(in136.path("ref_images.ref_image_2").isMissingNode());

        // 输出前缀已清理
        assertEquals("video/minimax-h3-accel", wf.path("92").path("inputs").path("filename_prefix").asText());
    }

    @Test
    void defaultsMegapixelsWhenNotProvided() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .prompt("a")
                .ratio("16:9")
                .model("minimax-h3-accel")
                .build();

        JsonNode wf = builder.build(cmd, List.of("a.png"));

        assertEquals(0.7, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
    }

    @Test
    void specExposesResolutionChoices() {
        var spec = builder.spec();
        assertEquals("minimax-h3-accel", spec.model());
        assertEquals("comfyui", spec.provider());
        assertTrue(spec.needImages());
        assertEquals(9, spec.imageMax());
        assertEquals(OutputType.VIDEO, spec.outputType());
        assertTrue(spec.megapixels().contains(0.7));
        assertTrue(spec.megapixels().contains(2.0));
    }
}
