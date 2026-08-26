package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.type.AssetNodeType;
import org.example.seedancegenarate.canvas.type.GenerateNodeType;
import org.example.seedancegenarate.canvas.type.TextNodeType;
import org.example.seedancegenarate.canvas.validator.AcyclicValidator;
import org.example.seedancegenarate.canvas.validator.NodeConfigValidator;
import org.example.seedancegenarate.canvas.validator.PortCapacityValidator;
import org.example.seedancegenarate.canvas.validator.PortCompatibilityValidator;
import org.example.seedancegenarate.canvas.validator.RunningNodeGuardValidator;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasEdge;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.CanvasEdgeMapper;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.service.CanvasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布增量保存的硬规则守卫（协议自 pipeline 侧迁移而来，语义不变、载体换成 canvas 表）：
 * 稳定身份、乐观并发、重放幂等、运行态字段保护、无环，外加画布独有的<b>端口类型与容量校验</b>。
 * 这些规则破一条，用户拖一下画布就可能毁掉正在跑的任务、丢掉另一个窗口的编辑，
 * 或把模型吃不下的输入连上去、白冻结一次钱。
 */
class CanvasMutationTest {

    private static final Long USER = 7L;
    private static final Long CID = 100L;

    private CanvasMapper canvasMapper;
    private CanvasNodeMapper nodeMapper;
    private CanvasEdgeMapper edgeMapper;
    private VideoEngine engine;
    private CanvasServiceImpl service;

    @BeforeEach
    void setUp() {
        canvasMapper = mock(CanvasMapper.class);
        nodeMapper = mock(CanvasNodeMapper.class);
        edgeMapper = mock(CanvasEdgeMapper.class);

        engine = mock(VideoEngine.class);
        VideoEngineRegistry engineRegistry = mock(VideoEngineRegistry.class);
        when(engineRegistry.get(anyString())).thenReturn(engine);
        // 默认：图生视频模型，最多 2 张参考图，无视频/音频参考
        givenModel(OutputType.VIDEO, false, 2, 0, 0);

        GenerateNodeType generate = new GenerateNodeType(engineRegistry);
        ReflectionTestUtils.setField(generate, "defaultProvider", "seedance");
        CanvasNodeTypeRegistry typeRegistry = new CanvasNodeTypeRegistry(
                List.of(new AssetNodeType(), new TextNodeType(), generate));

        service = new CanvasServiceImpl(canvasMapper, nodeMapper, edgeMapper, typeRegistry,
                List.of(new NodeConfigValidator(typeRegistry),
                        new PortCompatibilityValidator(typeRegistry),
                        new PortCapacityValidator(typeRegistry),
                        new AcyclicValidator(),
                        new RunningNodeGuardValidator()),
                new ObjectMapper());

        Canvas canvas = new Canvas();
        canvas.setId(CID);
        canvas.setUserId(USER);
        canvas.setVersion(3L);
        canvas.setStatus("DRAFT");
        when(canvasMapper.selectById(CID)).thenReturn(canvas);
        when(nodeMapper.selectList(any())).thenReturn(List.of());
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(canvasMapper.bumpVersion(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
    }

    private void givenModel(OutputType output, boolean needImages, int imageMax, int videoMax, int audioMax) {
        when(engine.models()).thenReturn(List.of(new ModelSpec(
                "seedance", "m1", "M1", needImages, needImages ? 1 : 0, imageMax,
                List.of("16:9"), 5, 10, List.of(5, 10), output, List.of(), videoMax, audioMax, false)));
    }

    private CanvasService.NodeUpsert gen(String key, int x) {
        return new CanvasService.NodeUpsert(key, "GENERATE", "镜头", x, 0, 260, null,
                "{\"provider\":\"seedance\",\"model\":\"m1\",\"prompt\":\"p\"}");
    }

    private CanvasService.NodeUpsert asset(String key, String mediaType) {
        return new CanvasService.NodeUpsert(key, "ASSET", "素材", 0, 0, 260, null,
                "{\"assetId\":1,\"mediaType\":\"" + mediaType + "\"}");
    }

    private CanvasService.CanvasMutation mut(String id, Long base,
                                             List<CanvasService.NodeUpsert> ups,
                                             List<String> dels,
                                             List<CanvasService.EdgeUpsert> edges) {
        return new CanvasService.CanvasMutation(id, base, null, ups, dels, edges, null);
    }

    private CanvasNode row(Long id, String key, String type, String status) {
        CanvasNode n = new CanvasNode();
        n.setId(id);
        n.setCanvasId(CID);
        n.setNodeKey(key);
        n.setNodeType(type);
        n.setStatus(status);
        n.setConfig("{\"provider\":\"seedance\",\"model\":\"m1\",\"prompt\":\"原始提示词\"}");
        n.setTaskId("tsk_abc");
        n.setOutput("{\"mediaType\":\"VIDEO\",\"url\":\"http://cdn/v.mp4\"}");
        return n;
    }

    @Test
    void upsertOnExistingKeyUpdatesSameRowInsteadOfRecreating() {
        // 测什么：同一 nodeKey 再次 upsert 走 updateById 打到原有行，绝不 delete+insert
        // 怎么算红：出现 insert 或 delete —— 退回「删旧插新」，节点主键漂移会让连线断裂、
        //          在途任务与节点失联、幂等键失效
        when(nodeMapper.selectList(any())).thenReturn(List.of(row(55L, "k1", "GENERATE", "PENDING")));

        service.applyMutation(USER, CID, mut("m1", 3L, List.of(gen("k1", 120)), null, null));

        ArgumentCaptor<CanvasNode> saved = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper).updateById(saved.capture());
        assertEquals(55L, saved.getValue().getId());
        assertEquals(120, saved.getValue().getPosX());
        verify(nodeMapper, never()).insert(any(CanvasNode.class));
        verify(nodeMapper, never()).delete(any());
    }

    @Test
    void upsertNeverWritesRuntimeFields() {
        // 测什么：upsert 写回的实体不含 status/taskId/output/submitRequestId/errorMsg
        // 怎么算红：这些字段出现在 patch 里 —— 用户拖节点的瞬间执行器正在回填终态，
        //          会把刚写入的 taskId/产物冲成 null，任务变孤儿、结果凭空消失
        when(nodeMapper.selectList(any())).thenReturn(List.of(row(55L, "k1", "GENERATE", "PENDING")));

        service.applyMutation(USER, CID, mut("m1", 3L, List.of(gen("k1", 10)), null, null));

        ArgumentCaptor<CanvasNode> saved = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper).updateById(saved.capture());
        CanvasNode patch = saved.getValue();
        assertNull(patch.getStatus());
        assertNull(patch.getTaskId());
        assertNull(patch.getOutput());
        assertNull(patch.getSubmitRequestId());
        assertNull(patch.getErrorMsg());
    }

    @Test
    void runningNodeAcceptsLayoutButIgnoresConfigEdits() {
        // 测什么：PROCESSING 节点只搬坐标，config 不搬
        // 怎么算红：配置被改写 —— 已提交的任务不会因此变化，用户看到的与实际生成的从此不一致
        when(nodeMapper.selectList(any())).thenReturn(List.of(row(55L, "k1", "GENERATE", "PROCESSING")));

        service.applyMutation(USER, CID, mut("m1", 3L, List.of(gen("k1", 88)), null, null));

        ArgumentCaptor<CanvasNode> saved = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper).updateById(saved.capture());
        assertEquals(88, saved.getValue().getPosX());
        assertNull(saved.getValue().getConfig());
    }

    @Test
    void staleBaseVersionConflictsWithZeroWrites() {
        // 测什么：CAS 不中（别的窗口已保存）→ 409，且本次事务零写入
        // 怎么算红：抛错前已经写了节点 —— 冲突留下改了一半的画布，比直接覆盖更难排查
        when(canvasMapper.bumpVersion(anyLong(), anyLong(), anyString(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 1L, List.of(gen("k1", 0)), null, null)));

        assertEquals(409, ex.getCode());
        verify(nodeMapper, never()).insert(any(CanvasNode.class));
        verify(nodeMapper, never()).updateById(any(CanvasNode.class));
        verify(nodeMapper, never()).delete(any());
        verify(edgeMapper, never()).insert(any(CanvasEdge.class));
        verify(edgeMapper, never()).delete(any());
    }

    @Test
    void replayedMutationIdIsRecognizedInsteadOfConflicting() {
        // 测什么：响应丢失后客户端重发同一 mutationId → 识别为重放，返回原版本号、不再写库
        // 怎么算红：重发被当成冲突（版本号已推进必然不匹配）或被重复应用 ——
        //          前者让用户看到假冲突，后者重复插入节点
        Canvas applied = new Canvas();
        applied.setId(CID);
        applied.setUserId(USER);
        applied.setVersion(4L);
        applied.setLastMutationId("m1");
        when(canvasMapper.selectById(CID)).thenReturn(applied);

        CanvasService.SaveAck ack = service.applyMutation(
                USER, CID, mut("m1", 3L, List.of(gen("k1", 0)), null, null));

        assertTrue(ack.replayed());
        assertEquals(4L, ack.version());
        verify(canvasMapper, never()).bumpVersion(anyLong(), anyLong(), anyString(), any());
        verify(nodeMapper, never()).insert(any(CanvasNode.class));
    }

    @Test
    void cyclicEdgesRejectedBeforeAnyWrite() {
        // 测什么：应用后成环（A-B-A）→ 拒绝且零写入。故意用「类型合法」的环
        //        （视频输出接视频参考口），否则会先被类型校验拦下，测不到环检测
        // 怎么算红：环被写进库 —— DAG 就绪判定永远无法满足，这条画布上的节点永久卡在待运行
        givenModel(OutputType.VIDEO, false, 0, 1, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 3L,
                        List.of(gen("a", 0), gen("b", 300)), null,
                        List.of(new CanvasService.EdgeUpsert("e1", "a", "out", "b", "video"),
                                new CanvasService.EdgeUpsert("e2", "b", "out", "a", "video")))));

        assertTrue(ex.getMessage().contains("环"));
        verify(canvasMapper, never()).bumpVersion(anyLong(), anyLong(), anyString(), any());
        verify(edgeMapper, never()).insert(any(CanvasEdge.class));
    }

    @Test
    void deletingRunningNodeIsRefused() {
        // 测什么：删除 PROCESSING 节点 → 拒绝且零写入
        // 怎么算红：运行中节点被删 —— 在途任务失去归属，终态回填找不到节点，钱冻结了却无处展示
        when(nodeMapper.selectList(any())).thenReturn(List.of(row(55L, "k1", "GENERATE", "PROCESSING")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 3L, null, List.of("k1"), null)));

        assertTrue(ex.getMessage().contains("生成中"));
        verify(nodeMapper, never()).delete(any());
        verify(canvasMapper, never()).bumpVersion(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void deletingNodeAlsoRemovesItsEdges() {
        // 测什么：删除节点时连带删除其相关边
        // 怎么算红：边残留 —— 悬空边指向不存在的节点，就绪判定会永远等一个不存在的上游
        when(nodeMapper.selectList(any())).thenReturn(List.of(row(55L, "k1", "GENERATE", "PENDING")));

        service.applyMutation(USER, CID, mut("m1", 3L, null, List.of("k1"), null));

        verify(nodeMapper).delete(any());
        verify(edgeMapper).delete(any());
    }

    @Test
    void savingDoesNotResetCanvasStatus() {
        // 测什么：增量保存不回写 canvas 状态
        // 怎么算红：每次自动保存都重置全局状态 —— 画布拖一下就把 RUNNING 打回 DRAFT
        service.applyMutation(USER, CID, mut("m1", 3L, List.of(gen("k1", 0)), null, null));

        verify(canvasMapper, never()).updateById(any(Canvas.class));
        verify(canvasMapper).bumpVersion(eq(CID), eq(3L), eq("m1"), any());
    }

    // ===== 以下是画布独有的端口校验（pipeline 侧没有这些概念）=====

    @Test
    void videoAssetCannotConnectToImagePort() {
        // 测什么：视频素材接到「参考图」口 → 拒绝（类型不匹配）
        // 怎么算红：类型不匹配的连线被存下 —— 运行时提交给模型必然失败，而钱已经冻结了
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 3L,
                        List.of(asset("a1", "VIDEO"), gen("g1", 300)), null,
                        List.of(new CanvasService.EdgeUpsert("e1", "a1", "out", "g1", "image")))));

        assertTrue(ex.getMessage().contains("类型不匹配"), ex.getMessage());
        verify(canvasMapper, never()).bumpVersion(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void imageAssetIntoImagePortIsAccepted() {
        // 测什么：图片素材接「参考图」口 → 放行（正常路径不被新校验误伤）
        // 怎么算红：合法连线被拒 —— 画布最基本的用法直接不可用
        service.applyMutation(USER, CID, mut("m1", 3L,
                List.of(asset("a1", "IMAGE"), gen("g1", 300)), null,
                List.of(new CanvasService.EdgeUpsert("e1", "a1", "out", "g1", "image"))));

        verify(edgeMapper).insert(any(CanvasEdge.class));
    }

    @Test
    void connectingToNonexistentPortIsRefused() {
        // 测什么：模型 audioMax=0 时不存在「参考音频」口 → 往该口连线被拒
        // 怎么算红：能连到模型根本没有的端口 —— 错误推迟到提交时才暴露
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 3L,
                        List.of(asset("a1", "AUDIO"), gen("g1", 300)), null,
                        List.of(new CanvasService.EdgeUpsert("e1", "a1", "out", "g1", "audio")))));

        assertTrue(ex.getMessage().contains("输入端口"), ex.getMessage());
    }

    @Test
    void exceedingPortCapacityIsRefused() {
        // 测什么：imageMax=2 的模型接入 3 张图 → 拒绝
        // 怎么算红：超容量连线被存下 —— 提交必被提供方拒绝，白跑一趟并冻结额度
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyMutation(USER, CID, mut("m1", 3L,
                        List.of(asset("a1", "IMAGE"), asset("a2", "IMAGE"), asset("a3", "IMAGE"), gen("g1", 300)),
                        null,
                        List.of(new CanvasService.EdgeUpsert("e1", "a1", "out", "g1", "image"),
                                new CanvasService.EdgeUpsert("e2", "a2", "out", "g1", "image"),
                                new CanvasService.EdgeUpsert("e3", "a3", "out", "g1", "image")))));

        assertTrue(ex.getMessage().contains("最多接入"), ex.getMessage());
    }

    @Test
    void newNonExecutableNodeStartsIdleNotPending() {
        // 测什么：素材/文本节点建出来是 IDLE（不产生任务、不进就绪扫描）
        // 怎么算红：素材节点是 PENDING —— 会被当作待运行节点提交，白扣一次钱
        service.applyMutation(USER, CID, mut("m1", 3L, List.of(asset("a1", "IMAGE")), null, null));

        ArgumentCaptor<CanvasNode> saved = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper).insert(saved.capture());
        assertEquals("IDLE", saved.getValue().getStatus());
    }
}
