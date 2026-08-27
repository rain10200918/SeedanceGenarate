package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Flux 2.0 图像编辑工作流构建器（model = flux2-image-edit）。
 * 支持「单图编辑」与「双图融合编辑」：
 * <ul>
 *   <li>1 张参考图：激活单图编辑子图（保持原图比例），注入 LoadImage(45)、Prompt(37)、Seed(39)</li>
 *   <li>2 张参考图：激活双图编辑子图（图1+图2融合），注入 LoadImage A(21)、LoadImage B(9)、ResolutionSelector(19)、Prompt(20)、Seed(13)</li>
 * </ul>
 * 输出走 SaveImage（节点 27 或 节点 2），poll 结果命中 outputs 的 "images" 列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Flux2ImageEditWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "flux2-image-edit";
    private static final String TEMPLATE_PATH = "comfyui/workflows/flux2-image-edit.json";

    // —— 单图编辑子图节点 (1 张图) ——
    private static final Set<String> SINGLE_IMAGE_NODES = Set.of(
            "27", "28", "29", "30", "34", "35", "36", "37", "39", "40", "41", "42", "43", "44", "45", "51", "57"
    );
    private static final String SINGLE_NODE_IMAGE = "45";      // LoadImage
    private static final String SINGLE_NODE_PROMPT = "37";     // PrimitiveStringMultiline
    private static final String SINGLE_NODE_SEED = "39";       // Seed (rgthree)
    private static final String SINGLE_NODE_SCALE = "51";      // ImageScaleToTotalPixels

    // —— 双图编辑子图节点 (2 张图) ——
    private static final Set<String> DUAL_IMAGE_NODES = Set.of(
            "2", "3", "4", "5", "6", "7", "8", "9", "11", "13", "14", "15", "16", "17", "18", "19", "20", "21", "141", "142"
    );
    private static final String DUAL_NODE_IMAGE_A = "21";      // LoadImage 1
    private static final String DUAL_NODE_IMAGE_B = "9";       // LoadImage 2
    private static final String DUAL_NODE_PROMPT = "20";       // PrimitiveStringMultiline
    private static final String DUAL_NODE_SEED = "13";         // Seed (rgthree)
    private static final String DUAL_NODE_RES = "19";          // ResolutionSelector
    private static final String DUAL_NODE_SCALE_A = "141";     // ImageScaleToTotalPixels 1
    private static final String DUAL_NODE_SCALE_B = "142";     // ImageScaleToTotalPixels 2

    private static final int IMAGE_MIN = 1;
    private static final int IMAGE_MAX = 2;
    private static final double DEFAULT_MEGAPIXELS = 1.0;

    /** 下发前端的比例顺序 */
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

    /** 可选分辨率档位（百万像素） */
    private static final List<Double> MEGAPIXELS =
            List.of(0.5, 0.7, 0.8, 1.0, 1.2, 1.5, 2.0);

    private final ObjectMapper objectMapper;
    private volatile JsonNode template;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "Flux 2.0 图像编辑",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, 0, 0, List.of(),
                OutputType.IMAGE, MEGAPIXELS);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        JsonNode rawTemplate = template();
        List<String> images = files.images();

        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Flux 2.0 图像编辑至少需要提供 1 张参考图");
        }

        double megapixels = command.getMegapixels() != null ? command.getMegapixels() : DEFAULT_MEGAPIXELS;
        long seed = ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L);
        String prompt = command.getPrompt() == null ? "" : command.getPrompt();

        if (images.size() == 1) {
            // —— 1. 单图编辑流程 ——
            return buildSingleImageWorkflow(rawTemplate, images.get(0), prompt, seed, megapixels);
        } else {
            // —— 2. 双图编辑流程 ——
            String ratio = command.getRatio();
            return buildDualImageWorkflow(rawTemplate, images.get(0), images.get(1), prompt, seed, ratio, megapixels);
        }
    }

    /** 构建单图编辑工作流（仅保留单图子图节点） */
    private ObjectNode buildSingleImageWorkflow(JsonNode rawTemplate, String imageName,
                                                String prompt, long seed, double megapixels) {
        ObjectNode root = objectMapper.createObjectNode();
        for (String nodeId : SINGLE_IMAGE_NODES) {
            JsonNode node = rawTemplate.get(nodeId);
            if (node != null) {
                root.set(nodeId, node.deepCopy());
            }
        }

        // 注入单图参数
        requireInputs(root, SINGLE_NODE_IMAGE).put("image", imageName);
        requireInputs(root, SINGLE_NODE_PROMPT).put("value", prompt);
        requireInputs(root, SINGLE_NODE_SEED).put("seed", seed);
        requireInputs(root, SINGLE_NODE_SCALE).put("megapixels", megapixels);

        log.debug("构建 Flux 2.0 单图编辑工作流: image={}, seed={}, megapixels={}", imageName, seed, megapixels);
        return root;
    }

    /** 构建双图编辑工作流（仅保留双图子图节点） */
    private ObjectNode buildDualImageWorkflow(JsonNode rawTemplate, String imageA, String imageB,
                                              String prompt, long seed, String ratio, double megapixels) {
        ObjectNode root = objectMapper.createObjectNode();
        for (String nodeId : DUAL_IMAGE_NODES) {
            JsonNode node = rawTemplate.get(nodeId);
            if (node != null) {
                root.set(nodeId, node.deepCopy());
            }
        }

        // 注入双图参数
        requireInputs(root, DUAL_NODE_IMAGE_A).put("image", imageA);
        requireInputs(root, DUAL_NODE_IMAGE_B).put("image", imageB);
        requireInputs(root, DUAL_NODE_PROMPT).put("value", prompt);
        requireInputs(root, DUAL_NODE_SEED).put("seed", seed);
        requireInputs(root, DUAL_NODE_SCALE_A).put("megapixels", megapixels);
        requireInputs(root, DUAL_NODE_SCALE_B).put("megapixels", megapixels);

        ObjectNode resNode = requireInputs(root, DUAL_NODE_RES);
        resNode.put("aspect_ratio", ratioLabel(ratio));
        resNode.put("megapixels", megapixels);

        log.debug("构建 Flux 2.0 双图编辑工作流: imageA={}, imageB={}, seed={}, ratio={}", imageA, imageB, seed, ratio);
        return root;
    }

    private String ratioLabel(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return RATIO_LABELS.get("2:3");
        }
        return RATIO_LABELS.getOrDefault(ratio.trim(), RATIO_LABELS.get("2:3"));
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("Flux 2.0 工作流模板缺少节点或 inputs: " + nodeId);
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
