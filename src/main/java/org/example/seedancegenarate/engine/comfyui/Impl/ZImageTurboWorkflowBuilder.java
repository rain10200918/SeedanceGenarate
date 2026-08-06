package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.comfyui.WorkflowBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Z-Image-Turbo「文生图」工作流构建器（model = z-image-turbo）。
 * ComfyUI 首个图片产物模型：无参考图、无时长，运行时注入提示词 / 随机种子 / 分辨率（由比例映射）。
 * 输出走 SaveImage（节点 9），poll 时 {@code extractVideoUrl} 会命中 outputs 的 "images" 键。
 */
@Component
@RequiredArgsConstructor
public class ZImageTurboWorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "z-image-turbo";
    private static final String TEMPLATE_PATH = "comfyui/workflows/z-image-turbo.json";

    // —— 模板固定节点 id ——
    private static final String NODE_PROMPT = "57:27";      // CLIPTextEncode
    private static final String NODE_SEED = "57:3";         // KSampler
    private static final String NODE_LATENT = "57:13";      // EmptySD3LatentImage

    /** 比例 → [宽, 高]（SDXL 常用桶，均为 32/64 的倍数，约 1MP） */
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

    /** 下发前端的比例顺序 */
    private static final List<String> RATIOS =
            List.of("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9");

    private final ObjectMapper objectMapper;
    private volatile JsonNode template;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "Z-Image-Turbo 文生图",
                false, 0, 0, RATIOS, 0, 0, List.of(), OutputType.IMAGE);
    }

    @Override
    public JsonNode build(GenerateCommand command, List<String> imageFilenames) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 提示词
        requireInputs(root, NODE_PROMPT).put("text", command.getPrompt() == null ? "" : command.getPrompt());
        // 随机种子
        requireInputs(root, NODE_SEED).put("seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        // 比例 → 分辨率（Map.of 不接受 null key，先归一）
        String ratio = command.getRatio() == null ? "16:9" : command.getRatio();
        int[] size = RATIO_SIZES.getOrDefault(ratio, RATIO_SIZES.get("1:1"));
        ObjectNode latent = requireInputs(root, NODE_LATENT);
        latent.put("width", size[0]);
        latent.put("height", size[1]);

        return root;
    }

    private ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.path("inputs").isObject()) {
            throw new IllegalStateException("Z-Image-Turbo 工作流模板缺少节点或 inputs: " + nodeId);
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
