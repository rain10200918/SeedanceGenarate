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
 * MiniMax-H3「首尾帧生视频高清版」工作流构建器（model = minimax-h3-fl2va-hd）。
 * 架构特点：
 * 1. 首尾帧注入：节点 114 加载首帧，节点 138 加载尾帧（单图时首尾共用）；
 * 2. 清晰度与比例调节：节点 159 (ResolutionSelector) 与节点 119 (ImageScaleToTotalPixels) 联动动态注入 megapixels；
 * 3. 时长调节：节点 134 注入 5~15 秒时长，经数学表达式换算视频帧数；
 * 4. 两段采样与 3D 潜空间放大：一段 6-step 采样后经 3D 放大器放大 2 倍，经 ManualSigmas 高清重采样精修；
 * 5. 产物落盘：节点 221 (二采高清) 为唯一保存产物，节点 210 (一采预览) 关闭 save_output。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3Fl2vaHdWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3-fl2va-hd";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-fl2va-hd.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_FIRST_FRAME = "114";   // LoadImage (加载首帧)
    private static final String NODE_LAST_FRAME = "138";    // LoadImage (加载尾帧)
    private static final String NODE_PROMPT = "227";        // PrimitiveStringMultiline (提示词)
    private static final String NODE_SEED_PASS1 = "130";    // RandomNoise (第一段采样噪波种子)
    private static final String NODE_SEED_PASS2 = "218";    // RandomNoise (第二段高清精修噪波种子)
    private static final String NODE_RES_SELECTOR = "159";  // ResolutionSelector (分辨率与比例)
    private static final String NODE_IMAGE_SCALE = "119";   // ImageScaleToTotalPixels (图像缩放百万像素)
    private static final String NODE_DURATION = "134";      // PrimitiveFloat (时长秒数)
    private static final String NODE_BOOL_T2V = "188";      // BOOLConstant (文生视频开关，图生填 false)
    private static final String NODE_PREVIEW_OUT = "210";   // VHS_VideoCombine (第一段低清预览)
    private static final String NODE_OUTPUT = "221";        // VHS_VideoCombine (二采高清最终唯一产物)

    private static final int IMAGE_MIN = 1;
    private static final int IMAGE_MAX = 2;
    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;
    private static final double DEFAULT_MEGAPIXELS = 0.3;

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

    /** 可选分辨率档位（百万像素），实际高清成片为 2 倍边长放大（4 倍面积） */
    private static final List<Double> MEGAPIXELS =
            List.of(0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.98, 1.0);

    private final ObjectMapper objectMapper;
    private volatile JsonNode template;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 首尾帧生视频 高清版",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, DURATION_MIN, DURATION_MAX, List.of(),
                OutputType.VIDEO, MEGAPIXELS);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 1. 注入首尾帧图像
        List<String> images = files != null ? files.images() : List.of();
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("MiniMax-H3 首尾帧生视频至少需要提供 1 张参考图（首帧）");
        }
        String firstFrame = images.get(0);
        String lastFrame = images.size() > 1 ? images.get(1) : firstFrame;
        requireInputs(root, NODE_FIRST_FRAME).put("image", firstFrame);
        requireInputs(root, NODE_LAST_FRAME).put("image", lastFrame);

        // 2. 注入提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());

        // 3. 注入两段采样独立随机种子
        requireInputs(root, NODE_SEED_PASS1).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        requireInputs(root, NODE_SEED_PASS2).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));

        // 4. 注入比例与清晰度（Megapixels）
        double megapixels = command.getMegapixels() == null ? DEFAULT_MEGAPIXELS : command.getMegapixels();
        ObjectNode resSelector = requireInputs(root, NODE_RES_SELECTOR);
        resSelector.put("aspect_ratio", ratioLabel(command.getRatio()));
        resSelector.put("megapixels", megapixels);

        if (root.has(NODE_IMAGE_SCALE)) {
            requireInputs(root, NODE_IMAGE_SCALE).put("megapixels", megapixels);
        }

        // 5. 注入时长（5~15 秒）
        requireInputs(root, NODE_DURATION).put("value", clampDuration(command.getDuration()));

        // 6. 首尾帧图生视频模式关闭 BOOLConstant (false)
        if (root.has(NODE_BOOL_T2V)) {
            requireInputs(root, NODE_BOOL_T2V).put("value", false);
        }

        // 7. 确保二采高清产物落盘，预览分支不重复保存
        if (root.has(NODE_PREVIEW_OUT)) {
            requireInputs(root, NODE_PREVIEW_OUT).put("save_output", false);
        }
        requireInputs(root, NODE_OUTPUT).put("save_output", true);

        return root;
    }

    private String ratioLabel(String ratio) {
        return RATIO_LABELS.getOrDefault(ratio == null ? "16:9" : ratio, RATIO_LABELS.get("16:9"));
    }

    private int clampDuration(Integer duration) {
        int d = duration == null ? 8 : duration;
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("MiniMax-H3 首尾帧生视频高清工作流模板缺少节点或 inputs: " + nodeId);
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
