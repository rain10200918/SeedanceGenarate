package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiniMax-H3「官方 4-step turbo」多参考生视频工作流构建器（model = minimax-h3-4step）。
 * 图片 / 视频 / 音频 三类参考素材：图片 ≤9、视频 ≤2（每段自带音轨，由 XB_VideoLoader 的 output[0] 帧 / output[2] 音轨
 * 同时连入 ref_videos / ref_video_audios）、独立音频 ≤2（LoadAudio）。至少需要一个图片或视频参考。
 * 运行时注入提示词 / 随机种子 / 比例 / megapixels / 时长，并按实际数量重建三类加载节点。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH34StepWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3-4step";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-4step.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "209";        // PrimitiveStringMultiline
    private static final String NODE_SEED = "182";          // RandomNoise
    private static final String NODE_PARAMS = "205";        // XB_HailuoH3VideoParams（比例 + megapixels + 时长）
    private static final String NODE_REF_TARGET = "167";    // MiniMaxH3ReferenceToVideo
    private static final String NODE_OUTPUT = "210";        // VHS_VideoCombine（filename_prefix）
    private static final String[] UI_ONLY_NODES = {"191", "211"};

    // 模板里的参考素材占位节点（完整示例形态），build 时整体剥离按实际数量重建
    private static final String[] TEMPLATE_IMAGE_NODES = {"194", "195", "213", "214"};  // LoadImage
    private static final String[] TEMPLATE_VIDEO_NODES = {"219"};                        // XB_VideoLoader
    private static final String[] TEMPLATE_AUDIO_NODES = {"217", "218"};                 // LoadAudio

    // 目标节点 167 的动态输入前缀
    private static final String REF_IMAGE_PREFIX = "ref_images.ref_image_";
    private static final String REF_VIDEO_PREFIX = "ref_videos.ref_video_";
    private static final String REF_VIDEO_AUDIO_PREFIX = "ref_video_audios.ref_video_audio_";
    private static final String REF_AUDIO_PREFIX = "ref_audios.ref_audio_";

    private static final int IMAGE_MIN = 0;      // 图片可选：视频也可作为参考
    private static final int IMAGE_MAX = 9;
    private static final int VIDEO_MAX = 2;
    private static final int AUDIO_MAX = 2;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;
    private static final double DEFAULT_MEGAPIXELS = 0.7;

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

    /** 可选分辨率档位（百万像素），由 XB_HailuoH3VideoParams 结合比例算出实际宽高 */
    private static final List<Double> MEGAPIXELS =
            List.of(0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.98, 1.0, 1.2, 1.5, 1.8, 2.0);

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
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 4-step 多参考生视频",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, DURATION_MIN, DURATION_MAX, List.of(),
                OutputType.VIDEO, MEGAPIXELS, VIDEO_MAX, AUDIO_MAX, true);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());
        // 随机种子
        requireInputs(root, NODE_SEED).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        // 比例 + 分辨率档位 + 时长（XB_HailuoH3VideoParams 由这三者算出宽高/帧数）
        ObjectNode params = requireInputs(root, NODE_PARAMS);
        params.put("aspect_ratio", ratioLabel(command.getRatio()));
        params.put("megapixels", command.getMegapixels() == null ? DEFAULT_MEGAPIXELS : command.getMegapixels());
        params.put("duration", clampDuration(command.getDuration()));

        // 三类参考素材：删模板占位加载节点，按实际数量重建并重连
        rebuildReferenceImages(root, files.images());
        rebuildReferenceVideos(root, files.videos());
        rebuildReferenceAudios(root, files.audios());

        // 剥掉与 API 执行无关的 UI 节点
        for (String id : UI_ONLY_NODES) {
            root.remove(id);
        }
        return root;
    }

    private void rebuildReferenceImages(ObjectNode root, List<String> filenames) {
        stripNodes(root, TEMPLATE_IMAGE_NODES);
        ObjectNode targetInputs = requireInputs(root, NODE_REF_TARGET);
        removeInputsByPrefix(targetInputs, REF_IMAGE_PREFIX);

        for (int i = 0; i < filenames.size(); i++) {
            String nodeId = "img_" + i;
            ObjectNode loadImage = objectMapper.createObjectNode();
            loadImage.putObject("inputs").put("image", filenames.get(i));
            loadImage.put("class_type", "LoadImage");
            loadImage.putObject("_meta").put("title", "ref 图片 " + i);
            root.set(nodeId, loadImage);

            targetInputs.set(REF_IMAGE_PREFIX + i, link(nodeId, 0));
        }
    }

    /**
     * 每个参考视频建一个 XB_VideoLoader：帧序列（output 0）接到 ref_videos.ref_video_i，
     * 音轨（output 2）接到 ref_video_audios.ref_video_audio_i。
     */
    private void rebuildReferenceVideos(ObjectNode root, List<String> filenames) {
        stripNodes(root, TEMPLATE_VIDEO_NODES);
        ObjectNode targetInputs = requireInputs(root, NODE_REF_TARGET);
        removeInputsByPrefix(targetInputs, REF_VIDEO_PREFIX);
        removeInputsByPrefix(targetInputs, REF_VIDEO_AUDIO_PREFIX);

        for (int i = 0; i < filenames.size(); i++) {
            String nodeId = "vid_" + i;
            ObjectNode loader = objectMapper.createObjectNode();
            ObjectNode inputs = loader.putObject("inputs");
            inputs.put("video", filenames.get(i));
            inputs.put("force_rate", 0);
            inputs.put("custom_width", 0);
            inputs.put("custom_height", 0);
            inputs.put("frame_load_cap", 0);
            inputs.put("skip_first_frames", 0);
            inputs.put("select_every_nth", 1);
            inputs.put("format", "AnimateDiff");
            loader.put("class_type", "XB_VideoLoader");
            loader.putObject("_meta").put("title", "ref 视频 " + i);
            root.set(nodeId, loader);

            targetInputs.set(REF_VIDEO_PREFIX + i, link(nodeId, 0));
            targetInputs.set(REF_VIDEO_AUDIO_PREFIX + i, link(nodeId, 2));
        }
    }

    private void rebuildReferenceAudios(ObjectNode root, List<String> filenames) {
        stripNodes(root, TEMPLATE_AUDIO_NODES);
        ObjectNode targetInputs = requireInputs(root, NODE_REF_TARGET);
        removeInputsByPrefix(targetInputs, REF_AUDIO_PREFIX);

        for (int i = 0; i < filenames.size(); i++) {
            String nodeId = "aud_" + i;
            ObjectNode loadAudio = objectMapper.createObjectNode();
            loadAudio.putObject("inputs").put("audio", filenames.get(i));
            loadAudio.put("class_type", "LoadAudio");
            loadAudio.putObject("_meta").put("title", "ref 音频 " + i);
            root.set(nodeId, loadAudio);

            targetInputs.set(REF_AUDIO_PREFIX + i, link(nodeId, 0));
        }
    }

    private void stripNodes(ObjectNode root, String[] ids) {
        for (String id : ids) {
            root.remove(id);
        }
    }

    private void removeInputsByPrefix(ObjectNode inputs, String prefix) {
        List<String> stale = new ArrayList<>();
        Iterator<String> it = inputs.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.startsWith(prefix)) {
                stale.add(f);
            }
        }
        stale.forEach(inputs::remove);
    }

    /** 构造 ComfyUI 连线 [nodeId, slot] */
    private ArrayNode link(String nodeId, int slot) {
        ArrayNode link = objectMapper.createArrayNode();
        link.add(nodeId);
        link.add(slot);
        return link;
    }

    private String ratioLabel(String ratio) {
        // Map.of 不接受 null key，先归一
        return RATIO_LABELS.getOrDefault(ratio == null ? "16:9" : ratio, RATIO_LABELS.get("16:9"));
    }

    private int clampDuration(Integer duration) {
        int d = duration == null ? 8 : duration;
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("MiniMax-H3 4-step 工作流模板缺少节点或 inputs: " + nodeId);
        }
        return (ObjectNode) node.get("inputs");
    }

    private JsonNode template() throws IOException {
        JsonNode t = template;
        if (t == null) {
            synchronized (this) {
                t = template;
                if (t == null) {
                    try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
                        t = objectMapper.readTree(in);
                    }
                    template = t;
                }
            }
        }
        return t;
    }
}
