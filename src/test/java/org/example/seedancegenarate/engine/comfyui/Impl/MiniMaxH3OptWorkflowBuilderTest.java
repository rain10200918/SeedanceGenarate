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
 * 纯单元测试：验证 MiniMax-H3 优化版（官方 ref2va 模板 + turbo 8-step LoRA）的注入
 * （prompt/seed/比例/megapixels/时长）与三类参考素材（图片 LoadImage / 视频 XB_VideoLoader 双连线 / 音频 LoadAudio）
 * 的重建逻辑。
 */
class MiniMaxH3OptWorkflowBuilderTest {

    private final MiniMaxH3OptWorkflowBuilder builder =
            new MiniMaxH3OptWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptAndRebuildsThreeRefTypes() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("猫咪在窗台回头")
                .duration(30)          // 超范围 → 夹取到 15
                .ratio("16:9")
                .megapixels(1.0)
                .model("minimax-h3-opt")
                .build();

        ReferenceFiles files = new ReferenceFiles(
                List.of("f0.png", "f1.jpg"),
                List.of("v0.mp4"),
                List.of("a0.wav", "a1.wav"));
        JsonNode wf = builder.build(cmd, files);

        // 注入：提示词 / 比例标签 / megapixels / 时长夹取 / 随机种子
        assertEquals("猫咪在窗台回头", wf.path("138").path("inputs").path("value").asText());
        assertEquals("16:9 (Widescreen)", wf.path("115").path("inputs").path("aspect_ratio").asText());
        assertEquals(1.0, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
        assertEquals(15, wf.path("132").path("inputs").path("value").asInt());
        assertNotEquals(796754478666701L, wf.path("129").path("inputs").path("noise_seed").asLong());

        // 模板占位节点被剥离：4 个 LoadImage + 8 个图片链路 Reroute
        for (String id : new String[]{"137", "139", "143", "160", "153", "154", "155", "156", "157", "158", "161", "162"}) {
            assertTrue(wf.path(id).isMissingNode(), "占位节点 " + id + " 应被剥离");
        }

        // 图片：按 2 张重建 LoadImage 并连到 ref_images
        assertEquals("f0.png", wf.path("img_0").path("inputs").path("image").asText());
        assertEquals("LoadImage", wf.path("img_0").path("class_type").asText());
        JsonNode in136 = wf.path("136").path("inputs");
        assertEquals("img_0", in136.path("ref_images.ref_image_0").get(0).asText());
        assertEquals("img_1", in136.path("ref_images.ref_image_1").get(0).asText());
        assertTrue(in136.path("ref_images.ref_image_2").isMissingNode());
        assertEquals("match", in136.path("ref_image_size").asText(), "非 ref 输入保持不变");

        // 视频：按 1 段重建 XB_VideoLoader，帧（out0）+ 音轨（out2）双连线
        assertEquals("v0.mp4", wf.path("vid_0").path("inputs").path("video").asText());
        assertEquals("XB_VideoLoader", wf.path("vid_0").path("class_type").asText());
        assertEquals(0, wf.path("vid_0").path("inputs").path("force_rate").asInt());
        assertEquals("AnimateDiff", wf.path("vid_0").path("inputs").path("format").asText());
        assertEquals("vid_0", in136.path("ref_videos.ref_video_0").get(0).asText());
        assertEquals(0, in136.path("ref_videos.ref_video_0").get(1).asInt());
        assertEquals("vid_0", in136.path("ref_video_audios.ref_video_audio_0").get(0).asText());
        assertEquals(2, in136.path("ref_video_audios.ref_video_audio_0").get(1).asInt());
        assertTrue(in136.path("ref_videos.ref_video_1").isMissingNode());

        // 音频：按 2 段重建 LoadAudio 并连到 ref_audios
        assertEquals("a0.wav", wf.path("aud_0").path("inputs").path("audio").asText());
        assertEquals("LoadAudio", wf.path("aud_0").path("class_type").asText());
        assertEquals("aud_0", in136.path("ref_audios.ref_audio_0").get(0).asText());
        assertEquals("aud_1", in136.path("ref_audios.ref_audio_1").get(0).asText());
        assertTrue(in136.path("ref_audios.ref_audio_2").isMissingNode());

        // 输出前缀已清理（模板转换时清成 video/minimax-h3-opt）
        assertEquals("video/minimax-h3-opt", wf.path("163").path("inputs").path("filename_prefix").asText());
        // 加速 LoRA 保留在采样链路（127 → 159 → 124/126）
        assertEquals("minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors",
                wf.path("159").path("inputs").path("lora_name").asText());
        assertEquals("159", wf.path("124").path("inputs").path("model").get(0).asText());
    }

    @Test
    void defaultsMegapixelsWhenNotProvided() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .prompt("a")
                .ratio("16:9")
                .model("minimax-h3-opt")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of("a.png"), List.of(), List.of()));

        assertEquals(0.7, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
    }

    @Test
    void specExposesReferenceCapabilities() {
        var spec = builder.spec();
        assertEquals("minimax-h3-opt", spec.model());
        assertEquals("comfyui", spec.provider());
        assertEquals("MiniMax-H3多参生视频 优化版", spec.label());
        assertEquals(OutputType.VIDEO, spec.outputType());
        // 图片 0..9 可选，但至少要有一个图片或视频参考
        assertTrue(spec.needImages());
        assertEquals(0, spec.imageMin());
        assertEquals(9, spec.imageMax());
        assertEquals(2, spec.videoMax());
        assertEquals(2, spec.audioMax());
        assertTrue(spec.needImageOrVideo());
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
        assertTrue(spec.ratios().contains("16:9"));
        assertTrue(spec.megapixels().contains(0.7));
        assertTrue(spec.megapixels().contains(2.0));
    }
}
