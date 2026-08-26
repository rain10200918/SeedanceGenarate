package org.example.seedancegenarate.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.canvas.type.AssetNodeType;
import org.example.seedancegenarate.canvas.type.GenerateNodeType;
import org.example.seedancegenarate.canvas.type.TextNodeType;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 画布节点端口推导守卫。核心主张：<b>生成节点的端口来自模型能力，不是写死的类型表</b> ——
 * 这条一破，画布就退回「所有节点长一个样」的分镜画法。
 */
class PortDerivationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private VideoEngine engine;
    private GenerateNodeType generateType;

    @BeforeEach
    void setUp() {
        engine = mock(VideoEngine.class);
        VideoEngineRegistry registry = mock(VideoEngineRegistry.class);
        when(registry.get(anyString())).thenReturn(engine);
        generateType = new GenerateNodeType(registry);
        ReflectionTestUtils.setField(generateType, "defaultProvider", "seedance");
    }

    private JsonNode config(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 造一个模型能力：图/视频/音频输入上限 + 输出类型 + 是否必须给图 */
    private void givenModel(String model, OutputType output, boolean needImages,
                            int imageMax, int videoMax, int audioMax) {
        when(engine.models()).thenReturn(List.of(new ModelSpec(
                "seedance", model, model, needImages, needImages ? 1 : 0, imageMax,
                List.of("16:9"), 5, 10, List.of(5, 10), output, List.of(), videoMax, audioMax, false)));
    }

    @Test
    void videoModelWithReferencesExposesImageVideoAudioPorts() {
        // 测什么：多参考的生视频模型 → 输出 VIDEO，且图/视频/音频三个输入口的 max 等于模型上限
        // 怎么算红：端口数量或 max 与 ModelSpec 不一致 —— 说明端口是写死的，
        //          换模型后前端会画出模型根本不支持的连接点，连上去必然提交失败
        givenModel("multi-ref", OutputType.VIDEO, false, 3, 2, 1);

        PortSpec spec = generateType.ports(config("{\"provider\":\"seedance\",\"model\":\"multi-ref\"}"));

        assertEquals(MediaType.VIDEO, spec.output());
        assertEquals(3, spec.input(InputPort.IMAGE).max());
        assertEquals(2, spec.input(InputPort.VIDEO).max());
        assertEquals(1, spec.input(InputPort.AUDIO).max());
        assertNotNull(spec.input(InputPort.PROMPT));
    }

    @Test
    void portsDisappearWhenModelDoesNotSupportThem() {
        // 测什么：videoMax=0 / audioMax=0 的模型 → 这两个端口根本不出现
        // 怎么算红：出现了不支持的端口 —— 用户能连上一个模型吃不下的输入，错误推迟到提交时才暴露
        givenModel("text2video", OutputType.VIDEO, false, 0, 0, 0);

        PortSpec spec = generateType.ports(config("{\"model\":\"text2video\"}"));

        assertNull(spec.input(InputPort.IMAGE));
        assertNull(spec.input(InputPort.VIDEO));
        assertNull(spec.input(InputPort.AUDIO));
        assertEquals(1, spec.inputs().size(), "只应剩提示词口");
    }

    @Test
    void imageModelOutputsImageAndMarksRequiredReference() {
        // 测什么：生图模型 → 输出 IMAGE；needImages=true 时参考图口是必填
        // 怎么算红：输出类型算错（下游按视频接线）或必填标记丢失（空输入直接提交，白扣一次钱）
        givenModel("qwen-edit", OutputType.IMAGE, true, 2, 0, 0);

        PortSpec spec = generateType.ports(config("{\"model\":\"qwen-edit\"}"));

        assertEquals(MediaType.IMAGE, spec.output());
        assertTrue(spec.input(InputPort.IMAGE).required());
        assertEquals(2, spec.input(InputPort.IMAGE).max());
    }

    @Test
    void unknownModelDegradesInsteadOfThrowing() {
        // 测什么：模型已下架/未选 → 端口退化为「只有提示词口」，ports() 不抛异常
        // 怎么算红：抛异常 —— 画布上只要有一个旧节点，整块画布就打不开
        when(engine.models()).thenReturn(List.of());

        PortSpec spec = generateType.ports(config("{\"model\":\"gone-model\"}"));

        assertEquals(1, spec.inputs().size());
        assertNotNull(spec.input(InputPort.PROMPT));
        // 但保存校验必须明确拒绝，避免静默留下坏节点
        assertThrows(BusinessException.class,
                () -> generateType.validateConfig(config("{\"model\":\"gone-model\"}")));
    }

    @Test
    void assetNodeOutputTypeFollowsItsMedia() {
        // 测什么：素材节点输出类型 = 素材自身媒体类型（它是唯一能产出 AUDIO 的节点）
        // 怎么算红：素材统一当图片 —— 音频/视频素材接不进对应端口，画布无法表达配音与参考视频
        AssetNodeType asset = new AssetNodeType();

        assertEquals(MediaType.AUDIO,
                asset.ports(config("{\"assetId\":1,\"mediaType\":\"AUDIO\"}")).output());
        assertEquals(MediaType.VIDEO,
                asset.ports(config("{\"assetId\":1,\"mediaType\":\"VIDEO\"}")).output());
        assertTrue(asset.ports(config("{\"assetId\":1}")).inputs().isEmpty(), "素材节点没有输入口");
        assertFalse(asset.executable(), "素材节点不产生任务、不计费");
    }

    @Test
    void assetNodeRejectsMissingAsset() {
        // 测什么：素材节点必须选素材，否则保存被拒
        // 怎么算红：空素材节点能存下 —— 运行时才发现没东西可用
        AssetNodeType asset = new AssetNodeType();
        assertThrows(BusinessException.class, () -> asset.validateConfig(config("{}")));
    }

    @Test
    void textNodeIsPureTextSource() {
        // 测什么：文本节点输出 TEXT、无输入口、不可执行
        // 怎么算红：文本节点被当成可执行节点 —— 会去提交任务并冻结钱
        TextNodeType text = new TextNodeType();
        PortSpec spec = text.ports(config("{\"content\":\"赛博朋克\"}"));

        assertEquals(MediaType.TEXT, spec.output());
        assertTrue(spec.inputs().isEmpty());
        assertFalse(text.executable());
    }

    @Test
    void registryIndexesByTypeAndRejectsUnknown() {
        // 测什么：注册表按 type() 建索引、all() 输出全部（驱动前端面板）、未知类型明确报错
        // 怎么算红：新增实现没被自动收录 —— 扩展点失效，加节点类型还得改 service/controller
        CanvasNodeTypeRegistry registry = new CanvasNodeTypeRegistry(
                List.of(new AssetNodeType(), new TextNodeType(), generateType));

        assertEquals(3, registry.all().size());
        assertEquals("ASSET", registry.get("ASSET").type());
        assertEquals("GENERATE", registry.get("GENERATE").type());
        assertThrows(BusinessException.class, () -> registry.get("NOPE"));
    }
}
