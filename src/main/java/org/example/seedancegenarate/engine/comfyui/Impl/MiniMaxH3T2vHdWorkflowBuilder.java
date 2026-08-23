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
 * MiniMax-H3「文生视频高清版」工作流构建器（model = minimax-h3-t2v-hd）。
 * 采用两段采样与 3D 潜空间放大（Latent Upscaler 3D 2x）架构：
 * 一段 6-step 快速采样生成基础潜空间，经 3D 放大器放大 2 倍并由 ManualSigmas 进行第二段高清重采样精修；
 * 最终输出节点为 221（VHS_VideoCombine，接收二采解码产物 235 和 212），一采预览分支已完全剥离，
 * 确保 ComfyUI 唯一执行并落盘的产物即为二次采样的超清视频。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3T2vHdWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3-t2v-hd";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-t2v-hd.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "227";        // PrimitiveStringMultiline (提示词)
    private static final String NODE_SEED_PASS1 = "130";    // RandomNoise (第一段采样噪波种子)
    private static final String NODE_SEED_PASS2 = "218";    // RandomNoise (第二段高清精修噪波种子)
    private static final String NODE_RES = "159";           // ResolutionSelector (分辨率与比例)
    private static final String NODE_DURATION = "134";      // PrimitiveFloat (时长秒)
    private static final String NODE_OUTPUT = "221";        // VHS_VideoCombine (二采高清最终唯一产物)

    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;
    private static final double DEFAULT_MEGAPIXELS = 0.4;

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
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 文生视频 高清版",
                false, 0, 0, RATIOS, DURATION_MIN, DURATION_MAX, List.of(),
                OutputType.VIDEO, MEGAPIXELS, 0, 0, false);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 1. 注入提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());

        // 2. 注入两段采样独立随机种子
        requireInputs(root, NODE_SEED_PASS1).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        requireInputs(root, NODE_SEED_PASS2).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));

        // 3. 比例 + 分辨率档位
        ObjectNode res = requireInputs(root, NODE_RES);
        res.put("aspect_ratio", ratioLabel(command.getRatio()));
        res.put("megapixels", command.getMegapixels() == null ? DEFAULT_MEGAPIXELS : command.getMegapixels());

        // 4. 时长（秒）
        requireInputs(root, NODE_DURATION).put("value", clampDuration(command.getDuration()));

        // 5. 确保二采产物为唯一且保存的落盘产物
        requireInputs(root, NODE_OUTPUT).put("save_output", true);

        return root;
    }

    private String ratioLabel(String ratio) {
        return RATIO_LABELS.getOrDefault(ratio == null ? "16:9" : ratio, RATIO_LABELS.get("16:9"));
    }

    private int clampDuration(Integer duration) {
        int d = duration == null ? 10 : duration;
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("MiniMax-H3 文生视频高清工作流模板缺少节点或 inputs: " + nodeId);
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
