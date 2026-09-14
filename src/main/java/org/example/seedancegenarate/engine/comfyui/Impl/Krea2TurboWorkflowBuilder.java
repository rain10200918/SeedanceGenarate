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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** User-supplied Krea2 graph; preserve its weights, LoRAs and sampling settings. */
@Component
@RequiredArgsConstructor
public class Krea2TurboWorkflowBuilder implements WorkflowBuilder {
    public static final String MODEL = "krea2-turbo";
    private final ObjectMapper objectMapper;
    private static final List<String> RATIOS = List.of("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9");
    // Exact ratios, dimensions divisible by 8, approximately one megapixel.
    private static final Map<String, int[]> SIZES = Map.of(
            "1:1", new int[]{1024, 1024}, "16:9", new int[]{1280, 720},
            "9:16", new int[]{720, 1280}, "4:3", new int[]{1152, 864},
            "3:4", new int[]{864, 1152}, "3:2", new int[]{1248, 832},
            "2:3", new int[]{832, 1248}, "21:9", new int[]{1568, 672});

    @Override public String model() { return MODEL; }
    @Override public String templatePath() { return "comfyui/workflows/krea2-turbo.json"; }
    @Override public ModelSpec spec() {
        return new ModelSpec("comfyui", MODEL, "Krea2 Turbo 文生图",
                false, 0, 0, RATIOS, 0, 0, List.of(), OutputType.IMAGE);
    }

    @Override public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root;
        try (var in = new ClassPathResource(templatePath()).getInputStream()) {
            root = (ObjectNode) objectMapper.readTree(in);
        }
        String ratio = command.getRatio() == null ? "16:9" : command.getRatio();
        int[] size = SIZES.get(ratio);
        if (size == null) throw new IllegalArgumentException("Krea2 Turbo 不支持画幅: " + ratio);
        inputs(root, "20").put("text", command.getPrompt() == null ? "" : command.getPrompt());
        inputs(root, "13").put("seed", ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));
        inputs(root, "31").put("width", size[0]).put("height", size[1]).put("batch_size", 1);
        inputs(root, "60").put("filename_prefix", "image/krea2-turbo");
        return root;
    }

    private ObjectNode inputs(ObjectNode root, String id) {
        if (!(root.path(id).path("inputs") instanceof ObjectNode inputs))
            throw new IllegalStateException("Krea2 工作流缺少 inputs: " + id);
        return inputs;
    }
}
