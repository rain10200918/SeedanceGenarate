package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.comfyui.ReferenceFiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试：验证 MiniMax Music 3 (minimax-music3) 音乐生成工作流：
 * 1. 结构化 Caption 与 Lyrics 的正则解析与注入；
 * 2. 纯文本提示词的自动兜底（歌词回退 [Instrumental]）；
 * 3. 时长夹取（30s ~ 300s）与随机种子注入；
 * 4. Spec 声明为 OutputType.AUDIO，无画幅/分辨率参数。
 */
class MiniMaxMusic3WorkflowBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MiniMaxMusic3WorkflowBuilder builder =
            new MiniMaxMusic3WorkflowBuilder(objectMapper);

    @Test
    void specExposesAudioProperties() {
        var spec = builder.spec();
        assertEquals("minimax-music3", spec.model());
        assertEquals("comfyui", spec.provider());
        assertEquals(OutputType.AUDIO, spec.outputType());
        assertTrue(spec.ratios().isEmpty(), "音频模型不应包含画幅比例");
        assertTrue(spec.megapixels().isEmpty(), "音频模型不应包含分辨率");
        assertFalse(spec.needImages());
        assertEquals(30, spec.durationMin());
        assertEquals(300, spec.durationMax());
        assertEquals(List.of(30, 60, 120, 180, 240, 300), spec.durations());
    }

    @Test
    void buildInjectsStructuredCaptionAndLyrics() throws Exception {
        String structuredPrompt = """
                1. Caption (English Only):
                Global Metadata: BPM 120, Key C Major, Pop / Synthwave.
                Vocal Details: Female lead, bright and energetic.
                Arrangement: Synthesizer lead, upbeat electronic drum groove.
                
                2. Lyrics (With Tags):
                [Intro]
                Ooh yeah...
                [Verse]
                Walking down the neon street,
                Dancing to the summer beat.
                [Chorus]
                Feel the sound, feel the light!
                """;

        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_AUDIO)
                .prompt(structuredPrompt)
                .duration(120)
                .model("minimax-music3")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of(), List.of(), List.of()));

        // 1. 验证节点 46 MiniMaxMusic3TextEncode
        JsonNode encodeInputs = wf.path("46").path("inputs");
        assertTrue(encodeInputs.path("caption").asText().contains("BPM 120"));
        assertTrue(encodeInputs.path("caption").asText().contains("Synthwave"));
        assertTrue(encodeInputs.path("lyrics").asText().contains("[Intro]"));
        assertTrue(encodeInputs.path("lyrics").asText().contains("[Chorus]"));
        assertEquals(120, encodeInputs.path("max_duration").asInt());

        // 2. 验证随机种子注入到节点 53
        assertNotEquals(665322912911460L, wf.path("53").path("inputs").path("seed").asLong());

        // 3. 验证产物节点 35 SaveAudioAdvanced
        JsonNode outputInputs = wf.path("35").path("inputs");
        assertEquals("audio/minimax-music3", outputInputs.path("filename_prefix").asText());
        assertEquals("mp3", outputInputs.path("format").asText());
    }

    @Test
    void buildHandlesPlainPromptWithDefaultInstrumental() throws Exception {
        GenerateCommand cmd = GenerateCommand.builder()
                .mode(GenerationMode.TEXT_TO_AUDIO)
                .prompt("夏日海滩城市流行乐，节奏轻快")
                .duration(10)          // 低于下限 30s → 夹取到 30
                .model("minimax-music3")
                .build();

        JsonNode wf = builder.build(cmd, new ReferenceFiles(List.of(), List.of(), List.of()));

        JsonNode encodeInputs = wf.path("46").path("inputs");
        assertEquals("夏日海滩城市流行乐，节奏轻快", encodeInputs.path("caption").asText());
        assertEquals("[Instrumental]", encodeInputs.path("lyrics").asText());
        assertEquals(30, encodeInputs.path("max_duration").asInt());
    }
}
