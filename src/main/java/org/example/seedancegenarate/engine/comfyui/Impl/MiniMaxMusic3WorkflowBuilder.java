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
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMax Music 3 音乐生成工作流构建器（model = minimax-music3）。
 * 架构特性：
 * 1. 产物为 MP3 高品质音频（OutputType.AUDIO），按次计费（FLAT 50 算力点）；
 * 2. 运行时双轨注入：自动从提示词中解析结构化 Caption（音乐描述词）与 Lyrics（带标签歌词）；
 * 3. 支持 30s ~ 300s 自由时长（max_duration），注入独立随机种子。
 */
@Component
@RequiredArgsConstructor
public class MiniMaxMusic3WorkflowBuilder implements WorkflowBuilder {

    public static final String MODEL = "minimax-music3";
    private static final String TEMPLATE_PATH = "comfyui/workflows/minimax-music3.json";

    // —— 模板固定节点 ID ——
    private static final String NODE_OUTPUT = "35";      // SaveAudioAdvanced
    private static final String NODE_TEXT_ENCODE = "46"; // MiniMaxMusic3TextEncode
    private static final String NODE_SEED = "53";        // SeedNode

    private static final int DURATION_MIN = 30;
    private static final int DURATION_MAX = 300;
    private static final int DURATION_DEFAULT = 60;
    private static final List<Integer> DURATIONS = List.of(30, 60, 120, 180, 240, 300);

    /**
     * 智能识别 AI 润色后的双段结构：
     * 1. Caption (English Only): ...
     * 2. Lyrics (With Tags): ...
     */
    private static final Pattern CAPTION_LYRICS_PATTERN = Pattern.compile(
            "(?:1\\.\\s*Caption(?:\\s*\\([A-Za-z\\s]+\\))?:?|Caption:?)\\s*([\\s\\S]*?)" +
            "(?:2\\.\\s*Lyrics(?:\\s*\\([A-Za-z\\s]+\\))?:?|Lyrics:?)\\s*([\\s\\S]*)",
            Pattern.CASE_INSENSITIVE
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
        return new ModelSpec("comfyui", MODEL, "MiniMax Music 3 音乐生成",
                false, 0, 0, List.of(), DURATION_MIN, DURATION_MAX, DURATIONS,
                OutputType.AUDIO, List.of(), 0, 0, false);
    }

    @Override
    public JsonNode build(GenerateCommand command, ReferenceFiles files) throws Exception {
        ObjectNode root = (ObjectNode) template().deepCopy();

        // 1. 解析提示词中的 Caption 与 Lyrics
        ParsedMusicPrompt parsed = parsePrompt(command.getPrompt());

        // 2. 注入文本编码节点
        ObjectNode textEncode = requireInputs(root, NODE_TEXT_ENCODE);
        textEncode.put("caption", parsed.caption());
        textEncode.put("lyrics", parsed.lyrics());
        textEncode.put("max_duration", clampDuration(command.getDuration()));

        // 3. 注入随机种子
        requireInputs(root, NODE_SEED).put("seed",
                ThreadLocalRandom.current().nextLong(1L, 1_000_000_000_000_000L));

        // 4. 产物配置
        ObjectNode output = requireInputs(root, NODE_OUTPUT);
        output.put("filename_prefix", "audio/" + MODEL);
        output.put("format", "mp3");

        return root;
    }

    /**
     * 智能解析用户提示词：
     * - 若包含 1. Caption / 2. Lyrics 结构，分别提取；
     * - 若为纯文本创意，整段作为 caption，歌词默认填 [Instrumental]。
     */
    public ParsedMusicPrompt parsePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new ParsedMusicPrompt("", "[Instrumental]");
        }
        String clean = prompt.trim();
        Matcher matcher = CAPTION_LYRICS_PATTERN.matcher(clean);
        if (matcher.find()) {
            String caption = matcher.group(1).trim();
            String lyrics = matcher.group(2).trim();
            if (lyrics.isEmpty()) {
                lyrics = "[Instrumental]";
            }
            return new ParsedMusicPrompt(caption, lyrics);
        }
        // 未使用标准结构时，整段作为风格描述，歌词置为 [Instrumental]
        return new ParsedMusicPrompt(clean, "[Instrumental]");
    }

    public record ParsedMusicPrompt(String caption, String lyrics) {}

    private int clampDuration(Integer duration) {
        int d = duration == null ? DURATION_DEFAULT : duration;
        return Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    private JsonNode template() throws IOException {
        JsonNode local = template;
        if (local == null) {
            synchronized (this) {
                local = template;
                if (local == null) {
                    try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
                        local = objectMapper.readTree(in);
                        template = local;
                    }
                }
            }
        }
        return local;
    }

    private static ObjectNode requireInputs(ObjectNode root, String nodeId) {
        JsonNode node = root.get(nodeId);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("工作流模板缺少节点: " + nodeId);
        }
        JsonNode inputs = node.get("inputs");
        if (inputs == null || !inputs.isObject()) {
            throw new IllegalStateException("工作流模板节点缺少 inputs: " + nodeId);
        }
        return (ObjectNode) inputs;
    }
}
