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
 * 纯单元测试：验证 MiniMax-H3 高清快速版 (minimaxh3-hd-fast) 的注入：
 * 1. 提示词 (217) / 随机种子 (129) / 比例与固定分辨率 0.9 (115) / 时长与 media_state (212)；
 * 2. 分辨率在 spec 中不暴露可选档位，且 build 时恒定写死 0.9；
 * 3. 多模态素材正确组装进 media_state JSON。
 */
class MiniMaxH3HdFastWorkflowBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MiniMaxH3HdFastWorkflowBuilder builder =
            new MiniMaxH3HdFastWorkflowBuilder(objectMapper);

    @Test
    void buildInjectsPromptAndFixedMegapixelsAndMediaState() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("夕阳下的海滩漫步")
                .duration(20)          // 超范围 → 夹取到 15
                .ratio("9:16")
                .megapixels(1.5)       // 即使命令传入其他值，也必须写死 0.9
                .model("minimaxh3-hd-fast")
                .build();

        ReferenceFiles files = new ReferenceFiles(
                List.of("img_ref1.png", "img_ref2.png"),
                List.of("video_ref1.mp4"),
                List.of("audio_ref1.wav"));
        JsonNode wf = builder.build(cmd, files);

        // 1. 提示词
        assertEquals("夕阳下的海滩漫步", wf.path("217").path("inputs").path("value").asText());

        // 2. 随机种子
        assertNotEquals(102364714705667L, wf.path("129").path("inputs").path("noise_seed").asLong());

        // 3. 比例与写死 0.9 分辨率
        JsonNode res = wf.path("115").path("inputs");
        assertEquals("9:16 (Portrait Widescreen)", res.path("aspect_ratio").asText());
        assertEquals(0.9, res.path("megapixels").asDouble(), 1e-9);
        assertEquals(32, res.path("multiple").asInt());

        // 4. 时长与 media_state
        JsonNode unified = wf.path("212").path("inputs");
        assertEquals(15, unified.path("duration").asInt());
        assertEquals("omni_reference", unified.path("mode").asText());

        String mediaStateStr = unified.path("media_state").asText();
        assertNotNull(mediaStateStr);
        JsonNode mediaState = objectMapper.readTree(mediaStateStr);

        // _ui 统计
        assertEquals(2, mediaState.path("_ui").path("image_count").asInt());
        assertEquals(1, mediaState.path("_ui").path("video_count").asInt());
        assertEquals(1, mediaState.path("_ui").path("audio_count").asInt());

        // ref_image_1 & ref_image_2
        assertEquals("img_ref1.png", mediaState.path("ref_image_1").path("path").asText());
        assertEquals("image", mediaState.path("ref_image_1").path("kind").asText());
        assertEquals("img_ref2.png", mediaState.path("ref_image_2").path("path").asText());

        // ref_video_1
        assertEquals("video_ref1.mp4", mediaState.path("ref_video_1").path("path").asText());
        assertEquals("video", mediaState.path("ref_video_1").path("kind").asText());
        assertTrue(mediaState.path("ref_video_1").path("use_audio").asBoolean());

        // ref_audio_1
        assertEquals("audio_ref1.wav", mediaState.path("ref_audio_1").path("path").asText());
        assertEquals("audio", mediaState.path("ref_audio_1").path("kind").asText());

        // 5. 产物保存设置
        JsonNode output = wf.path("189").path("inputs");
        assertEquals("video/minimaxh3-hd-fast", output.path("filename_prefix").asText());
        assertTrue(output.path("save_output").asBoolean());
    }

    @Test
    void specDoesNotExposeMegapixelsOptions() {
        var spec = builder.spec();
        assertEquals("minimaxh3-hd-fast", spec.model());
        assertEquals("comfyui", spec.provider());
        assertEquals(OutputType.VIDEO, spec.outputType());
        assertTrue(spec.megapixels().isEmpty(), "分辨率不让用户选择，megapixels 应为空列表");
        assertEquals(0, spec.imageMin());
        assertEquals(9, spec.imageMax());
        assertEquals(3, spec.videoMax());
        assertEquals(3, spec.audioMax());
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
    }
}
