package org.example.seedancegenarate.engine.comfyui.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.OutputType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Krea2TurboWorkflowBuilderTest {
    private final Krea2TurboWorkflowBuilder builder = new Krea2TurboWorkflowBuilder(new ObjectMapper());

    // 【测什么】真实输出连接使用31号尺寸节点，原LoRA与采样配置保持。
    // 【怎么算红】改错到未连接的4号节点，或断开解码/保存连接会失败。
    @Test void injectsConnectedGraphAndPreservesSettings() throws Exception {
        var graph = builder.build(GenerateCommand.builder().prompt("石猴出世").ratio("16:9").build(), null);
        assertEquals("石猴出世", graph.at("/20/inputs/text").asText());
        assertEquals("31", graph.at("/13/inputs/latent_image/0").asText());
        assertEquals(1280, graph.at("/31/inputs/width").asInt());
        assertEquals(720, graph.at("/31/inputs/height").asInt());
        assertEquals(1536, graph.at("/4/inputs/width").asInt());
        assertEquals(12, graph.at("/13/inputs/steps").asInt());
        assertEquals("simple", graph.at("/13/inputs/scheduler").asText());
        assertEquals("25", graph.at("/13/inputs/model/0").asText());
        assertTrue(graph.at("/25/inputs/lora_2/on").asBoolean());
        assertEquals(0.8, graph.at("/25/inputs/lora_6/strength").asDouble());
        assertEquals("13", graph.at("/16/inputs/samples/0").asText());
        assertEquals("16", graph.at("/60/inputs/images/0").asText());
        assertEquals("SaveImage", graph.at("/60/class_type").asText());
        assertEquals("image/krea2-turbo", graph.at("/60/inputs/filename_prefix").asText());
        assertTrue(graph.at("/13/inputs/seed").asLong() > 0);
    }

    // 【测什么】声明的每种画幅准确、无图片输入要求、请求之间无模板污染。
    // 【怎么算红】比例桶颠倒或输出误声明为VIDEO、请求间共享修改会失败。
    @Test void ratiosAndIndependentRequests() throws Exception {
        assertEquals(OutputType.IMAGE, builder.spec().outputType());
        assertEquals(0, builder.spec().imageMax());
        assertFalse(builder.spec().needImages());
        for (String ratio : builder.spec().ratios()) {
            var g = builder.build(GenerateCommand.builder().prompt(ratio).ratio(ratio).build(), null);
            var parts = ratio.split(":");
            int w = g.at("/31/inputs/width").asInt(), h = g.at("/31/inputs/height").asInt();
            assertEquals(w * Integer.parseInt(parts[1]), h * Integer.parseInt(parts[0]));
            assertEquals(0, w % 8); assertEquals(0, h % 8);
        }
        var first = builder.build(GenerateCommand.builder().prompt("first").build(), null);
        builder.build(GenerateCommand.builder().prompt("second").ratio("1:1").build(), null);
        assertEquals("first", first.at("/20/inputs/text").asText());
        assertEquals(1280, first.at("/31/inputs/width").asInt());
        assertThrows(IllegalArgumentException.class, () -> builder.build(GenerateCommand.builder().ratio("invalid").build(), null));
    }
}
