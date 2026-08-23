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
 * 单元测试：验证 MiniMax-H3 文生视频高清版（minimax-h3-t2v-hd）
 * 1. 参数正确注入（提示词、两段采样随机种子、分辨率宽高比例、时长秒数）；
 * 2. 验证保存的正是二次采样的超清产物（节点 221 为唯一落盘产物，预览分支已剥离）；
 * 3. 验证放大与精修链路（首段 135 → 分离 207 → 3D 放大 209 → 拼回 208 → 二采精修 213 → 解码 211/212 → 221）。
 */
class MiniMaxH3T2vHdWorkflowBuilderTest {

    private final MiniMaxH3T2vHdWorkflowBuilder builder =
            new MiniMaxH3T2vHdWorkflowBuilder(new ObjectMapper());

    @Test
    void specMatchesExpectedTextToVideoHd() {
        var spec = builder.spec();
        assertEquals("minimax-h3-t2v-hd", spec.model());
        assertEquals("comfyui", spec.provider());
        assertFalse(spec.needImages());
        assertEquals(0, spec.imageMin());
        assertEquals(0, spec.imageMax());
        assertEquals(OutputType.VIDEO, spec.outputType());
        assertTrue(spec.megapixels().contains(0.4));
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
    }

    @Test
    void buildInjectsPromptSeedResolutionDuration() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_VIDEO)
                .prompt("赛博朋克雨夜街道，霓虹闪烁")
                .duration(12)
                .ratio("9:16")
                .megapixels(0.5)
                .model("minimax-h3-t2v-hd")
                .build();

        ReferenceFiles files = new ReferenceFiles(List.of(), List.of(), List.of());
        JsonNode wf = builder.build(cmd, files);

        // 1. 提示词注入
        assertEquals("赛博朋克雨夜街道，霓虹闪烁", wf.path("227").path("inputs").path("value").asText());

        // 2. 分辨率比例与 megapixels
        assertEquals("9:16 (Portrait Widescreen)", wf.path("159").path("inputs").path("aspect_ratio").asText());
        assertEquals(0.5, wf.path("159").path("inputs").path("megapixels").asDouble(), 1e-9);

        // 3. 时长注入
        assertEquals(12, wf.path("134").path("inputs").path("value").asInt());

        // 4. 两段独立随机种子
        assertNotEquals(42L, wf.path("130").path("inputs").path("noise_seed").asLong());
        assertNotEquals(42L, wf.path("218").path("inputs").path("noise_seed").asLong());
    }

    @Test
    void outputComesFromSecondPassOnly() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_VIDEO)
                .prompt("测试视频")
                .ratio("16:9")
                .model("minimax-h3-t2v-hd")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of(), List.of(), List.of()));

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
