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
 * MiniMax-H3「官方加速」参考生视频工作流构建器（model = minimax-h3-accel）。
 * 与 {@link MiniMaxH3WorkflowBuilder} 同为 ref2va（含原生音频），但采用加速权重栈，且分辨率可选：
 * ResolutionSelector(节点 115) 由 aspect_ratio + megapixels 算出输出宽高。
 * 运行时注入提示词 / 随机种子 / 比例 / megapixels / 时长，并按参考图张数重建 LoadImage 与连线。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxH3AccelWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-h3-accel";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-h3-accel.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "138";        // PrimitiveStringMultiline
    private static final String NODE_SEED = "129";          // RandomNoise
    private static final String NODE_RES = "115";           // ResolutionSelector（aspect_ratio + megapixels）
    private static final String NODE_DURATION = "132";      // PrimitiveFloat（时长秒，喂给数学表达式换算帧数）
    private static final String NODE_REF_TARGET = "136";    // MiniMaxH3ReferenceToVideo
    private static final String[] TEMPLATE_REF_NODES = {"137", "139", "143"};
    private static final String REF_INPUT_PREFIX = "ref_images.ref_image_";

    private static final int DURATION_MIN = 5;
    private static final int DURATION_MAX = 15;
    private static final int IMAGE_MIN = 1;
    private static final int IMAGE_MAX = 9;
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

    /** 可选分辨率档位（百万像素），由 ResolutionSelector 结合比例算出实际宽高 */
    private static final List<Double> MEGAPIXELS =
            List.of(0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.98, 1.0, 1.2, 1.5, 1.8, 2.0);

    private final ObjectMapper objectMapper;
    private volatile JsonNode template;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "MiniMax-H3参考生视频 官方加速",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, DURATION_MIN, DURATION_MAX, List.of(),
                OutputType.VIDEO, MEGAPIXELS);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 提示词
        requireInputs(root, NODE_PROMPT).put("value", command.getPrompt() == null ? "" : command.getPrompt());
        // 随机种子
        requireInputs(root, NODE_SEED).put("noise_seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        // 比例 + 分辨率档位（ResolutionSelector 算宽高）
        ObjectNode res = requireInputs(root, NODE_RES);
        res.put("aspect_ratio", ratioLabel(command.getRatio()));
        res.put("megapixels", command.getMegapixels() == null ? DEFAULT_MEGAPIXELS : command.getMegapixels());
        // 时长（秒）
        requireInputs(root, NODE_DURATION).put("value", clampDuration(command.getDuration()));

        // 参考图：删模板占位 LoadImage，按实际张数重建并重连 ref_image_i
        rebuildReferenceImages(root, files.images());

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
            throw new IllegalStateException("MiniMax-H3 加速工作流模板缺少节点或 inputs: " + nodeId);
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
