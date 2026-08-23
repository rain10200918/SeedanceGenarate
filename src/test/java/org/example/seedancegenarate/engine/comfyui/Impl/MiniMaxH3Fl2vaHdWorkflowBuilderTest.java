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
 * 单元测试：验证 MiniMax-H3 首尾帧生视频高清版（minimax-h3-fl2va-hd）
 * 1. 参数正确注入（首尾帧图像、提示词、两段采样随机种子、分辨率比例、Megapixels 清晰度、5~15 秒时长）；
 * 2. 验证落盘保存的正是二次采样的超清产物（节点 221 为全图唯一落盘产物，预览分支已剥离）；
 * 3. 验证完整二采放大与精修链路（首段 135 → 潜空间分离 207 → 3D 放大 209 → 拼回 208 → 二采采样 213 → 解码 211/212 → 221）；
 * 4. 验证单图与双图情况下的首尾帧分发逻辑。
 */
class MiniMaxH3Fl2vaHdWorkflowBuilderTest {

    private final MiniMaxH3Fl2vaHdWorkflowBuilder builder =
            new MiniMaxH3Fl2vaHdWorkflowBuilder(new ObjectMapper());

    @Test
    void specMatchesExpectedFl2vaHd() {
        var spec = builder.spec();
        assertEquals("minimax-h3-fl2va-hd", spec.model());
        assertEquals("comfyui", spec.provider());
        assertTrue(spec.needImages());
        assertEquals(1, spec.imageMin());
        assertEquals(2, spec.imageMax());
        assertEquals(OutputType.VIDEO, spec.outputType());
        assertTrue(spec.megapixels().contains(0.3));
        assertTrue(spec.megapixels().contains(0.8));
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
    }

    @Test
    void buildInjectsFirstAndLastFramesAndParameters() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("蜘蛛侠在高楼间飞跃，最终落入办公室")
                .duration(10)
                .ratio("16:9")
                .megapixels(0.6)
                .model("minimax-h3-fl2va-hd")
                .build();

        ReferenceFiles files = new ReferenceFiles(
                List.of("first_frame.jpg", "last_frame.jpg"),
                List.of(),
                List.of()
        );
        JsonNode wf = builder.build(cmd, files);

        // 1. 首尾帧注入
        assertEquals("first_frame.jpg", wf.path("114").path("inputs").path("image").asText());
        assertEquals("last_frame.jpg", wf.path("138").path("inputs").path("image").asText());

        // 2. 提示词注入
        assertEquals("蜘蛛侠在高楼间飞跃，最终落入办公室", wf.path("227").path("inputs").path("value").asText());

        // 3. 分辨率比例与 megapixels 清晰度
        assertEquals("16:9 (Widescreen)", wf.path("159").path("inputs").path("aspect_ratio").asText());
        assertEquals(0.6, wf.path("159").path("inputs").path("megapixels").asDouble(), 1e-9);
        assertEquals(0.6, wf.path("119").path("inputs").path("megapixels").asDouble(), 1e-9);

        // 4. 时长注入 (10秒)
        assertEquals(10, wf.path("134").path("inputs").path("value").asInt());

        // 5. 两段独立随机噪波种子
        assertNotEquals(42L, wf.path("130").path("inputs").path("noise_seed").asLong());
        assertNotEquals(42L, wf.path("218").path("inputs").path("noise_seed").asLong());

        // 6. 二采最终落盘设置
        assertTrue(wf.path("221").path("inputs").path("save_output").asBoolean());
    }

    @Test
    void singleImageUsesSameForFirstAndLastFrame() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("单一首帧向后演进")
                .duration(5)
                .ratio("9:16")
                .model("minimax-h3-fl2va-hd")
                .build();

        ReferenceFiles files = new ReferenceFiles(
                List.of("solo_frame.png"),
                List.of(),
                List.of()
        );
        JsonNode wf = builder.build(cmd, files);

        assertEquals("solo_frame.png", wf.path("114").path("inputs").path("image").asText());
        assertEquals("solo_frame.png", wf.path("138").path("inputs").path("image").asText());
        assertEquals("9:16 (Portrait Widescreen)", wf.path("159").path("inputs").path("aspect_ratio").asText());
        assertEquals(0.3, wf.path("159").path("inputs").path("megapixels").asDouble(), 1e-9);
    }

    @Test
    void outputComesFromSecondPassOnly() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("首尾帧二次采样测试")
                .ratio("16:9")
                .model("minimax-h3-fl2va-hd")
                .build();

        ReferenceFiles files = new ReferenceFiles(List.of("f1.jpg", "f2.jpg"), List.of(), List.of());
        JsonNode wf = builder.build(cmd, files);

        // 验证全图只有一个唯一的 VHS_VideoCombine 节点（221），即二次采样的产物
        long combines = countClassType(wf, "VHS_VideoCombine");
        assertEquals(1, combines, "工作流中应只有唯一一个二采合并落盘节点");

        JsonNode out = wf.path("221").path("inputs");
        assertEquals("VHS_VideoCombine", wf.path("221").path("class_type").asText());
        assertTrue(out.path("save_output").asBoolean(), "221 必须开启 save_output");
        assertEquals("video/minimaxh3", out.path("filename_prefix").asText());

        // 画面取自 VRAM_Debug 235 ← VAEDecode 211（接收二采采样器 213 的 samples）
        assertEquals("235", out.path("images").get(0).asText());
        assertEquals("211", wf.path("235").path("inputs").path("any_input").get(0).asText());
        assertEquals("213", wf.path("211").path("inputs").path("samples").get(0).asText());

        // 音频取自 VAEDecodeAudio 212（接收二采采样器 213 的 samples）
        assertEquals("212", out.path("audio").get(0).asText());
        assertEquals("213", wf.path("212").path("inputs").path("samples").get(0).asText());

        // 完整二采链路：首段采样 135 → 潜空间分离 207 → 3D 潜空间放大器 209 (scale 2) → 拼回 208 → 二采采样器 213
        assertEquals("135", wf.path("207").path("inputs").path("av_latent").get(0).asText());
        assertEquals("207", wf.path("209").path("inputs").path("latent").get(0).asText());
        assertEquals("229", wf.path("209").path("inputs").path("scale").get(0).asText());
        assertEquals("209", wf.path("208").path("inputs").path("video_latent").get(0).asText());
        assertEquals("207", wf.path("208").path("inputs").path("audio_latent").get(0).asText());
        assertEquals("208", wf.path("213").path("inputs").path("latent_image").get(0).asText());
    }

    private long countClassType(JsonNode root, String classType) {
        long count = 0;
        for (JsonNode n : root) {
            if (classType.equals(n.path("class_type").asText())) {
                count++;
            }
        }
        return count;
    }
}
