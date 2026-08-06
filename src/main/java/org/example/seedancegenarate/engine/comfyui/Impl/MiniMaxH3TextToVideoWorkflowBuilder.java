package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiniMax-H3「文生视频」工作流构建器（model = minimax-h3-t2v）。
 * 无参考图，运行时仅注入提示词 / 随机种子 / 比例 / 时长。与 {@link MiniMaxH3WorkflowBuilder}（参考生视频）
 * 共用同一套 XB_HailuoH3VideoParams 节点，只是工作流结构不同、不重建 LoadImage。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3TextToVideoWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3-t2v";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-t2v.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "187";
    private static final String NODE_SEED = "173";
    private static final String NODE_PARAMS = "185";
    private static final String[] UI_ONLY_NODES = {"192", "196"};

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
    public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 文生视频",
                false, 0, 0, RATIOS, DURATION_MIN, DURATION_MAX, List.of());
    }

    @Override
    public JsonNode build(GenerateCommand command, List<String> imageFilenames) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());
        // 随机种子（否则同参数出同结果）
        requireInputs(root, NODE_SEED).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        // 比例 + 时长
        ObjectNode params = requireInputs(root, NODE_PARAMS);
        params.put("aspect_ratio", ratioLabel(command.getRatio()));
        params.put("duration", clampDuration(command.getDuration()));

        // 文生视频无参考图；仅剥掉与 API 执行无关的 UI 节点
        for (String id : UI_ONLY_NODES) {
            root.remove(id);
        }
        return root;
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
            throw new IllegalStateException("MiniMax-H3 文生视频工作流模板缺少节点或 inputs: " + nodeId);
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
