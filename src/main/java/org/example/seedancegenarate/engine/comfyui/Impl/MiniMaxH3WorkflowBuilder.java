package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
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
 * MiniMax-H3「参考生视频」工作流构建器（model = minimax-h3）。
 * 从 resources 载入模板图，运行时注入提示词 / 随机种子 / 比例 / 时长，并按参考图张数重建 LoadImage 与连线。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3WorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-ref2v.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "170";
    private static final String NODE_SEED = "131";
    private static final String NODE_PARAMS = "161";
    private static final String NODE_REF_TARGET = "167";
    private static final String[] TEMPLATE_REF_NODES = {"114", "169", "176"};
    private static final String[] UI_ONLY_NODES = {"159", "164", "165"};
    private static final String REF_INPUT_PREFIX = "ref_images.ref_image_";

    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;
    private static final int IMAGE_MIN = 1;
    private static final int IMAGE_MAX = 9;

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
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3 参考生视频",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, DURATION_MIN, DURATION_MAX, List.of());
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
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

        // 参考图：删掉模板里的 3 个 LoadImage 与 ref 连线，按实际张数重建
        rebuildReferenceImages(root, files.images());

        // 剥掉与 API 执行无关的 UI 节点
        for (String id : UI_ONLY_NODES) {
            root.remove(id);
        }
        return root;
    }

    private void rebuildReferenceImages(ObjectNode root, List<String> imageFilenames) {
        for (String id : TEMPLATE_REF_NODES) {
            root.remove(id);
        }
        ObjectNode targetInputs = requireInputs(root, NODE_REF_TARGET);
        List<String> stale = new ArrayList<>();
        Iterator<String> it = targetInputs.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.startsWith(REF_INPUT_PREFIX)) {
                stale.add(f);
            }
        }
        stale.forEach(targetInputs::remove);

        for (int i = 0; i < imageFilenames.size(); i++) {
            String nodeId = "img_" + i;
            ObjectNode loadImage = objectMapper.createObjectNode();
            loadImage.putObject("inputs").put("image", imageFilenames.get(i));
            loadImage.put("class_type", "LoadImage");
            loadImage.putObject("_meta").put("title", "ref " + i);
            root.set(nodeId, loadImage);

            ArrayNode link = objectMapper.createArrayNode();
            link.add(nodeId);
            link.add(0);
            targetInputs.set(REF_INPUT_PREFIX + i, link);
        }
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
            throw new IllegalStateException("MiniMax-H3 工作流模板缺少节点或 inputs: " + nodeId);
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
