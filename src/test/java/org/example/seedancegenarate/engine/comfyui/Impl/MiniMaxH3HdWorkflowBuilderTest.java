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
 * 纯单元测试：验证 MiniMax-H3 高清优化版（6-step 首段 + 3D 潜空间 2 倍放大 + ManualSigmas 精修段）的
 * 注入（prompt/双段 seed/比例/megapixels/时长）、三类参考素材重建，以及成片来自精修段（167 ← 158/157）。
 */
class MiniMaxH3HdWorkflowBuilderTest {

    private final MiniMaxH3HdWorkflowBuilder builder =
            new MiniMaxH3HdWorkflowBuilder(new ObjectMapper());

    @Test
    void buildInjectsPromptAndRebuildsThreeRefTypes() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.IMAGE_TO_VIDEO)
                .prompt("猫咪在窗台回头")
                .duration(30)          // 超范围 → 夹取到 15
                .ratio("9:16")
                .megapixels(0.4)
                .model("minimax-h3-hd")
                .build();

        ReferenceFiles files = new ReferenceFiles(
                List.of("f0.png", "f1.jpg"),
                List.of("v0.mp4"),
                List.of("a0.wav", "a1.wav"));
        JsonNode wf = builder.build(cmd, files);

        // 注入：提示词 / 比例标签 / megapixels / 时长夹取
        assertEquals("猫咪在窗台回头", wf.path("138").path("inputs").path("value").asText());
        assertEquals("9:16 (Portrait Widescreen)", wf.path("115").path("inputs").path("aspect_ratio").asText());
        assertEquals(0.4, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
        assertEquals(15, wf.path("132").path("inputs").path("value").asInt());
        // 两段采样各自换掉模板里的固定种子
        assertNotEquals(43L, wf.path("129").path("inputs").path("noise_seed").asLong());
        assertNotEquals(42L, wf.path("159").path("inputs").path("noise_seed").asLong());

        // 模板占位图片节点被剥离
        assertTrue(wf.path("137").isMissingNode(), "占位节点 137 应被剥离");

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
    }

    @Test
    void outputComesFromUpscaledSecondPassOnly() throws Exception {
        // 怎么算红：成片节点若接回首段解码（或存在第二个 save_output 的合并节点），断言失败
        JsonNode wf = builder.build(
                GenerateCommand.builder().prompt("a").ratio("16:9").model("minimax-h3-hd").build(),
                new ReferenceFiles(List.of("a.png"), List.of(), List.of()));

        JsonNode out = wf.path("167").path("inputs");
        assertEquals("VHS_VideoCombine", wf.path("167").path("class_type").asText());
        assertTrue(out.path("save_output").asBoolean(), "167 是唯一落盘产物");
        assertEquals("158", out.path("images").get(0).asText(), "画面取精修段 VAEDecode");
        assertEquals("157", out.path("audio").get(0).asText(), "音频取精修段 VAEDecodeAudio");
        assertEquals("video/minimax-h3-hd", out.path("filename_prefix").asText());

        // 只有一个 VHS_VideoCombine：预览分支已剥离，不会多出一份产物干扰取回
        long combines = countClassType(wf, "VHS_VideoCombine");
        assertEquals(1, combines, "预览用的合并节点不应保留");

        // 放大链路：首段 125 → 分离 165 → 3D 放大 163（scale 2）→ 拼回 164 → 精修 154
        assertEquals("125", wf.path("165").path("inputs").path("av_latent").get(0).asText());
        assertEquals("165", wf.path("163").path("inputs").path("latent").get(0).asText());
        assertEquals(2, wf.path("163").path("inputs").path("scale").asInt());
        assertEquals("163", wf.path("164").path("inputs").path("video_latent").get(0).asText());
        assertEquals("165", wf.path("164").path("inputs").path("audio_latent").get(0).asText());
        assertEquals(1, wf.path("164").path("inputs").path("audio_latent").get(1).asInt());
        assertEquals("164", wf.path("154").path("inputs").path("latent_image").get(0).asText());
        assertEquals("162", wf.path("154").path("inputs").path("sigmas").get(0).asText());
        assertEquals("0.85, 0.7250, 0.4219, 0.0", wf.path("162").path("inputs").path("sigmas").asText());

        // 两段共用 turbo LoRA + SAGE 补丁
        assertEquals("minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors",
                wf.path("152").path("inputs").path("lora_name").asText());
        assertEquals("152", wf.path("172").path("inputs").path("model").get(0).asText());
        assertEquals("152", wf.path("171").path("inputs").path("model").get(0).asText());
        assertEquals("172", wf.path("126").path("inputs").path("model").get(0).asText());
        assertEquals("171", wf.path("160").path("inputs").path("model").get(0).asText());
    }

    private long countClassType(JsonNode workflow, String classType) {
        long n = 0;
        for (JsonNode node : workflow) {
            if (classType.equals(node.path("class_type").asText())) {
                n++;
            }
        }
        return n;
    }

    @Test
    void defaultsMegapixelsWhenNotProvided() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .prompt("a")
                .ratio("16:9")
                .model("minimax-h3-hd")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of("a.png"), List.of(), List.of()));

        assertEquals(0.3, wf.path("115").path("inputs").path("megapixels").asDouble(), 1e-9);
    }

    @Test
    void specExposesReferenceCapabilities() {
        var spec = builder.spec();
        assertEquals("minimax-h3-hd", spec.model());
        assertEquals("comfyui", spec.provider());
        assertEquals("MiniMax-H3多参生视频 高清优化版", spec.label());
        assertEquals(OutputType.VIDEO, spec.outputType());
        assertTrue(spec.needImages());
        assertEquals(0, spec.imageMin());
        assertEquals(9, spec.imageMax());
        assertEquals(3, spec.videoMax());
        assertEquals(3, spec.audioMax());
        assertTrue(spec.needImageOrVideo());
        assertEquals(5, spec.durationMin());
        assertEquals(15, spec.durationMax());
        assertTrue(spec.ratios().contains("16:9"));
        assertTrue(spec.megapixels().contains(0.3));
    }
}
