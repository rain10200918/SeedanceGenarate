package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiniMax-H3「高清快速版」全能参考生视频工作流构建器（model = minimaxh3-hd-fast）。
 * 架构特点：
 * 1. 采用 Yusu MiniMax H3 Unified (omni_reference 全模态参考) + fl2v turbo 8-step LoRA 方案；
 * 2. 分辨率固定为 0.9 百万像素 (写死 0.9，前端不暴露选择器)；
 * 3. 运行时注入提示词、随机种子、宽高比、时长（5~15s）以及 media_state 多模态素材映射；
 * 4. 支持参考图 (≤9)、参考视频 (≤3)、参考音频 (≤3)。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3HdFastWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimaxh3-hd-fast";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimaxh3-hd-fast.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_RES = "115";        // ResolutionSelector（aspect_ratio + megapixels 写死 0.9）
    private static final String NODE_SEED = "129";       // RandomNoise
    private static final String NODE_OUTPUT = "189";     // VHS_VideoCombine
    private static final String NODE_UNIFIED = "212";    // MiniMaxH3Unified
    private static final String NODE_PROMPT = "217";     // PrimitiveStringMultiline

    private static final double FIXED_MEGAPIXELS = 0.9;
    private static final int IMAGE_MIN = 0;
    private static final int IMAGE_MAX = 9;
    private static final int VIDEO_MAX = 3;
    private static final int AUDIO_MAX = 3;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;

    private static final List<String> RATIOS =
            List.of("1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9", "21:9");

    private static final Map<String, String> RATIO_LABELS = Map.of(
            "1:1", "1:1 (Square)",
            "2:3", "2:3 (Portrait Photo)",
            "3:2", "3:2 (Photo)",
            "3:4", "3:4 (Portrait Standard)",
            "4:3", "4:3 (Standard)",
            "9:16", "9:16 (Portrait Widescreen)",
            "16:9", "16:9 (Widescreen)",
            "21:9", "21:9 (Ultrawide)"
    );

    private final ObjectMapper objectMapper;
    private volatile JsonNode template;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public String templatePath() {
        return TEMPLATE_PATH;
    }

    @Override
    public ModelSpec spec() {
        // megapixels 传 List.of()，前端 /options 下发时不带分辨率档位，不让用户选择
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 高清快速版",
                false, IMAGE_MIN, IMAGE_MAX, RATIOS, DURATION_MIN, DURATION_MAX, List.of(),
                OutputType.VIDEO, List.of(), VIDEO_MAX, AUDIO_MAX, false);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 1. 注入提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());

        // 2. 注入随机种子
        requireInputs(root, NODE_SEED).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));

        // 3. 注入比例与固定分辨率 (0.9 Mpx)
        ObjectNode res = requireInputs(root, NODE_RES);
        res.put("aspect_ratio", ratioLabel(command.getRatio()));
        res.put("megapixels", FIXED_MEGAPIXELS);
        res.put("multiple", 32);

        // 4. 注入时长与 media_state
        ObjectNode unified = requireInputs(root, NODE_UNIFIED);
        unified.put("duration", clampDuration(command.getDuration()));
        unified.put("media_state", buildMediaState(files));

        // 5. 产物设置
        ObjectNode output = requireInputs(root, NODE_OUTPUT);
        output.put("filename_prefix", "video/" + MODEL);
        output.put("save_output", true);

        return root;
    }

    private String buildMediaState(ReferenceFiles files) throws Exception {
        ObjectNode state = objectMapper.createObjectNode();
        List<String> images = (files != null && files.images() != null) ? files.images() : List.of();
        List<String> videos = (files != null && files.videos() != null) ? files.videos() : List.of();
        List<String> audios = (files != null && files.audios() != null) ? files.audios() : List.of();

        ObjectNode ui = state.putObject("_ui");
        ui.put("image_count", images.size());
        ui.put("video_count", videos.size());
        ui.put("audio_count", audios.size());

        for (int i = 0; i < images.size(); i++) {
            String filename = images.get(i);
            ObjectNode item = state.putObject("ref_image_" + (i + 1));
            item.put("path", filename);
            item.put("name", filename);
            item.put("kind", "image");
            item.put("duration", 0);
            item.put("has_video", false);
            item.put("has_audio", false);
            item.put("trim_start", 0);
            item.put("trim_end", 0);
            item.put("use_audio", false);
        }

        for (int i = 0; i < videos.size(); i++) {
            String filename = videos.get(i);
            ObjectNode item = state.putObject("ref_video_" + (i + 1));
            item.put("path", filename);
            item.put("name", filename);
            item.put("kind", "video");
            item.put("has_video", true);
            item.put("has_audio", true);
            item.put("use_audio", true);
            item.put("trim_start", 0);
            item.put("trim_end", 0);
        }

        for (int i = 0; i < audios.size(); i++) {
            String filename = audios.get(i);
            ObjectNode item = state.putObject("ref_audio_" + (i + 1));
            item.put("path", filename);
            item.put("name", filename);
            item.put("kind", "audio");
            item.put("has_video", false);
            item.put("has_audio", true);
            item.put("use_audio", true);
            item.put("trim_start", 0);
            item.put("trim_end", 0);
        }

        return objectMapper.writeValueAsString(state);
    }

    private String ratioLabel(String ratio) {
        return RATIO_LABELS.getOrDefault(ratio == null ? "16:9" : ratio, RATIO_LABELS.get("16:9"));
    }

    private int clampDuration(Integer duration) {
        int d = duration == null ? 8 : duration;
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    private JsonNode template() throws IOException {
        JsonNode local = template;
        if (local == null) {
            synchronized (this) {
                local = template;
                if (local == null) {
                    try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
                        local = objectMapper.readTree(in);
                        template = local;
                    }
                }
            }
        }
        return local;
    }

    private static ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("工作流模板缺少节点: " + nodeId);
        }
        JsonNode inputs = node.get("inputs");
        if (inputs == null || !inputs.isObject()) {
            throw new IllegalStateException("工作流模板节点缺少 inputs: " + nodeId);
        }
        return (ObjectNode) inputs;
    }
}
