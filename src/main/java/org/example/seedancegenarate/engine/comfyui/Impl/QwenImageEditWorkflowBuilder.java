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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Qwen-Image-Edit「图生图」工作流构建器（model = qwen-image-edit）。
 * 多参考图编辑（TextEncodeQwenImageEditPlus 支持 image1..image3），运行时注入提示词 / 随机种子 /
 * 输出分辨率（由比例映射），并按实际张数（1..3）重建 LoadImage→缩放 链接到 image1..N。
 * 输出走 SaveImage（节点 36），poll 时 {@code extractVideoUrl} 命中 outputs 的 "images" 键。
 */
@Component
@RequiredArgsConstructor
public class QwenImageEditWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "qwen-image-edit";
    private static final String TEMPLATE_PATH = "comfyui/workflows/qwen-image-edit.json";

    // —— 模板里的固定节点 id ——
    private static final String NODE_PROMPT = "48";     // TextEncodeQwenImageEditPlus（正向，挂参考图）
    private static final String NODE_SEED = "42";       // KSampler
    private static final String NODE_LATENT = "49";     // EmptySD3LatentImage（输出画布）
    /** 模板单张参考图占位：LoadImage(46)→Scale(39)→VAEEncode(52, 悬空未用)，build 时整体删除按实际张数重建 */
    private static final String[] TEMPLATE_IMAGE_NODES = {"46", "39", "52"};

    private static final int IMAGE_MIN = 1;
    private static final int IMAGE_MAX = 3;   // TextEncodeQwenImageEditPlus 的 image1..image3

    /** 下发前端的比例顺序 */
    private static final List<String> RATIOS =
            List.of("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9");

    /** 比例 → [宽, 高]（约 1MP，均为 32 的倍数），写入输出画布 EmptySD3LatentImage */
    private static final Map<String, int[]> RATIO_SIZES = Map.of(
            "1:1", new int[]{1024, 1024},
            "16:9", new int[]{1344, 768},
            "9:16", new int[]{768, 1344},
            "4:3", new int[]{1152, 896},
            "3:4", new int[]{896, 1152},
            "3:2", new int[]{1216, 832},
            "2:3", new int[]{832, 1216},
            "21:9", new int[]{1536, 640}
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
        return new ModelSpec("comfyui", MODEL, "Qwen-Image-Edit 图生图",
                true, IMAGE_MIN, IMAGE_MAX, RATIOS, 0, 0, List.of(), OutputType.IMAGE);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 提示词
        requireInputs(root, NODE_PROMPT).put("prompt", command.getPrompt() == null ? "" : command.getPrompt());
        // 随机种子（否则同参数出同图）
        requireInputs(root, NODE_SEED).put("seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        // 比例 → 输出画布分辨率
        // Map.of 不接受 null key，先归一
        String ratio = command.getRatio() == null ? "16:9" : command.getRatio();
        int[] size = RATIO_SIZES.getOrDefault(ratio, RATIO_SIZES.get("1:1"));
        ObjectNode latent = requireInputs(root, NODE_LATENT);
        latent.put("width", size[0]);
        latent.put("height", size[1]);

        // 参考图：删模板单张占位，按实际张数（1..3）重建 LoadImage→缩放 并接到 image1..N
        rebuildReferenceImages(root, files.images());

        return root;
    }

    private void rebuildReferenceImages(ObjectNode root, List<String> imageFilenames) {
        for (String id : TEMPLATE_IMAGE_NODES) {
            root.remove(id);
        }
        ObjectNode encoderInputs = requireInputs(root, NODE_PROMPT);
        for (int i = 1; i <= IMAGE_MAX; i++) {
            encoderInputs.remove("image" + i);
        }
        int count = Math.min(imageFilenames.size(), IMAGE_MAX);
        for (int i = 0; i < count; i++) {
            String loadId = "qie_load_" + i;
            String scaleId = "qie_scale_" + i;

            ObjectNode loadImage = objectMapper.createObjectNode();
            loadImage.putObject("inputs").put("image", imageFilenames.get(i));
            loadImage.put("class_type", "LoadImage");
            loadImage.putObject("_meta").put("title", "参考图 " + (i + 1));
            root.set(loadId, loadImage);

            ObjectNode scale = objectMapper.createObjectNode();
            ObjectNode scaleInputs = scale.putObject("inputs");
            scaleInputs.put("upscale_method", "lanczos");
            scaleInputs.put("megapixels", 0.8);
            scaleInputs.put("resolution_steps", 61);
            scaleInputs.set("image", link(loadId));
            scale.put("class_type", "ImageScaleToTotalPixels");
            scale.putObject("_meta").put("title", "缩放参考图 " + (i + 1));
            root.set(scaleId, scale);

            encoderInputs.set("image" + (i + 1), link(scaleId));
        }
    }

    /** 构造 ComfyUI 连线 [nodeId, 0] */
    private ArrayNode link(String nodeId) {
        ArrayNode link = objectMapper.createArrayNode();
        link.add(nodeId);
        link.add(0);
        return link;
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("Qwen-Image-Edit 工作流模板缺少节点或 inputs: " + nodeId);
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
