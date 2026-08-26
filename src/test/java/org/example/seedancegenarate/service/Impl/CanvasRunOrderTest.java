package org.example.seedancegenarate.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.type.AssetNodeType;
import org.example.seedancegenarate.canvas.type.GenerateNodeType;
import org.example.seedancegenarate.canvas.type.TextNodeType;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasEdge;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.CanvasEdgeMapper;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CanvasArtifactResolver;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画布 DAG 执行守卫：<b>连线必须真的决定执行顺序</b>。
 * 这条破了，下游会在上游还没产出结果时就提交 —— 拿不到参考图却已经冻结了钱。
 */
class CanvasRunOrderTest {

    private static final Long USER = 7L;
    private static final Long CID = 100L;

    private CanvasMapper canvasMapper;
    private CanvasNodeMapper nodeMapper;
    private CanvasEdgeMapper edgeMapper;
    private AsyncJobService asyncJobService;
    private VideoSubmitService submitService;
    private CanvasArtifactResolver artifactResolver;
    private VideoTaskService videoTaskService;
    private VideoEngine engine;
    private CanvasRunServiceImpl service;

    private final List<CanvasNode> nodes = new ArrayList<>();
    private final List<CanvasEdge> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        canvasMapper = mock(CanvasMapper.class);
        nodeMapper = mock(CanvasNodeMapper.class);
        edgeMapper = mock(CanvasEdgeMapper.class);
        asyncJobService = mock(AsyncJobService.class);
        submitService = mock(VideoSubmitService.class);

        engine = mock(VideoEngine.class);
        VideoEngineRegistry engineRegistry = mock(VideoEngineRegistry.class);
        when(engineRegistry.get(anyString())).thenReturn(engine);
        // 图生视频模型：必须给参考图，最多 2 张
        when(engine.models()).thenReturn(List.of(new ModelSpec(
                "seedance", "m1", "M1", true, 1, 2, List.of("16:9"), 5, 10,
                List.of(5, 10), OutputType.VIDEO, List.of(), 0, 0, false)));

        GenerateNodeType generate = new GenerateNodeType(engineRegistry);
        ReflectionTestUtils.setField(generate, "defaultProvider", "seedance");
        CanvasNodeTypeRegistry typeRegistry = new CanvasNodeTypeRegistry(
                List.of(new AssetNodeType(), new TextNodeType(), generate));

        // 默认原样透传：多数用例不关心地址解析，只有专门的两条才让它换/抛
        artifactResolver = mock(CanvasArtifactResolver.class);
        when(artifactResolver.toFetchable(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        videoTaskService = mock(VideoTaskService.class);

        service = new CanvasRunServiceImpl(canvasMapper, nodeMapper, edgeMapper, typeRegistry,
                asyncJobService, submitService, artifactResolver, videoTaskService, new ObjectMapper());

        Canvas canvas = new Canvas();
        canvas.setId(CID);
        canvas.setUserId(USER);
        canvas.setStatus("DRAFT");
        when(canvasMapper.selectById(CID)).thenReturn(canvas);
        when(nodeMapper.selectList(any())).thenReturn(nodes);
        when(edgeMapper.selectList(any())).thenReturn(edges);
    }

    private CanvasNode gen(Long id, String key, String status) {
        CanvasNode n = new CanvasNode();
        n.setId(id);
        n.setCanvasId(CID);
        n.setNodeKey(key);
        n.setNodeType("GENERATE");
        n.setTitle(key);
        n.setStatus(status);
        n.setConfig("{\"provider\":\"seedance\",\"model\":\"m1\",\"prompt\":\"p\",\"duration\":5,\"ratio\":\"16:9\"}");
        nodes.add(n);
        return n;
    }

    private CanvasNode asset(Long id, String key, String url) {
        CanvasNode n = new CanvasNode();
        n.setId(id);
        n.setCanvasId(CID);
        n.setNodeKey(key);
        n.setNodeType("ASSET");
        n.setTitle(key);
        n.setStatus("IDLE");
        n.setConfig("{\"assetId\":1,\"mediaType\":\"IMAGE\",\"url\":\"" + url + "\"}");
        nodes.add(n);
        return n;
    }

    private void link(String from, String to, String port) {
        CanvasEdge e = new CanvasEdge();
        e.setId((long) (edges.size() + 1));
        e.setCanvasId(CID);
        e.setEdgeKey("e" + (edges.size() + 1));
        e.setFromNodeKey(from);
        e.setFromPort("out");
        e.setToNodeKey(to);
        e.setToPort(port);
        edges.add(e);
    }

    @Test
    void onlyReadyNodesAreEnqueuedDownstreamIsBlocked() {
        // 测什么：素材→A→B 的链条，run 只入队 A（上游是素材，已就绪）；B 因上游 A 未成功被标 BLOCKED
        // 怎么算红：B 也被入队 —— 它会在拿不到 A 的产物时就提交，冻结了钱却生成不出预期结果，
        //          连线等于白画
        asset(1L, "a", "http://cdn/i.png");
        gen(2L, "A", "PENDING");
        gen(3L, "B", "PENDING");
        link("a", "A", "image");
        link("A", "B", "image");

        List<CanvasNode> enqueued = service.run(USER, CID);

        assertEquals(1, enqueued.size());
        assertEquals("A", enqueued.get(0).getNodeKey());
        verify(asyncJobService).enqueue(eq(CanvasRunServiceImpl.JOB_TYPE),
                eq(CanvasRunServiceImpl.jobKey(CID, 2L)), anyString());
        verify(asyncJobService, never()).enqueue(eq(CanvasRunServiceImpl.JOB_TYPE),
                eq(CanvasRunServiceImpl.jobKey(CID, 3L)), anyString());

        // B 被标 BLOCKED 且带原因
        ArgumentCaptor<CanvasNode> patches = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream()
                        .anyMatch(p -> "BLOCKED".equals(p.getStatus()) && p.getId().equals(3L)),
                "上游未产出时下游必须标 BLOCKED");
    }

    @Test
    void upstreamSuccessAdvancesDownstream() {
        // 测什么：A 终态 SUCCESS 后，下游 B 变就绪并被入队（这就是「连线决定顺序」）
        // 怎么算红：上游成功后下游不动 —— 画布跑一半就停住，用户得手动点每个节点
        asset(1L, "a", "http://cdn/i.png");
        CanvasNode a = gen(2L, "A", "PROCESSING");
        a.setTaskId("tsk_A");
        gen(3L, "B", "BLOCKED");
        link("a", "A", "image");
        link("A", "B", "image");
        when(nodeMapper.selectOne(any())).thenReturn(a);

        service.applyTaskFinished("tsk_A", "SUCCESS", "http://cdn/a.mp4", null);

        verify(asyncJobService).enqueue(eq(CanvasRunServiceImpl.JOB_TYPE),
                eq(CanvasRunServiceImpl.jobKey(CID, 3L)), anyString());
    }

    @Test
    void upstreamFailureBlocksDownstreamWithoutEnqueue() {
        // 测什么：A 失败 → B 保持 BLOCKED 且不入队（不冻结钱）
        // 怎么算红：上游失败还提交下游 —— 必然生成失败，白冻结白解冻，用户看到一串莫名失败
        asset(1L, "a", "http://cdn/i.png");
        CanvasNode a = gen(2L, "A", "PROCESSING");
        a.setTaskId("tsk_A");
        gen(3L, "B", "BLOCKED");
        link("a", "A", "image");
        link("A", "B", "image");
        when(nodeMapper.selectOne(any())).thenReturn(a);

        service.applyTaskFinished("tsk_A", "FAILED", null, "内容审核未通过");

        verify(asyncJobService, never()).enqueue(anyString(),
                eq(CanvasRunServiceImpl.jobKey(CID, 3L)), anyString());
    }

    @Test
    void upstreamOutputsAreAssembledIntoSubmitByPort() throws Exception {
        // 测什么：提交时把「图片口」的上游产物装进 imageUrls，「提示词口」的文本追加到 prompt
        // 怎么算红：端口没被翻译成 SubmitRequest 的对应字段 —— 用户连的参考图根本没传给模型，
        //          画布连线成了纯装饰
        asset(1L, "a", "http://cdn/ref.png");
        CanvasNode g = gen(2L, "A", "PROCESSING");
        CanvasNode text = new CanvasNode();
        text.setId(9L);
        text.setCanvasId(CID);
        text.setNodeKey("t");
        text.setNodeType("TEXT");
        text.setStatus("IDLE");
        text.setConfig("{\"content\":\"赛博朋克风格\"}");
        nodes.add(text);
        link("a", "A", "image");
        link("t", "A", "prompt");
        g.setSubmitRequestId("canvas:2:req");
        when(nodeMapper.selectById(2L)).thenReturn(g);

        VideoTask task = new VideoTask();
        task.setBizTaskId("tsk_new");
        when(submitService.submit(any())).thenReturn(task);

        service.submitNodeForJob(2L);

        ArgumentCaptor<VideoSubmitService.SubmitRequest> captor =
                ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submitService).submit(captor.capture());
        VideoSubmitService.SubmitRequest req = captor.getValue();
        assertEquals(List.of("http://cdn/ref.png"), req.imageUrls());
        assertTrue(req.prompt().contains("赛博朋克风格"), "提示词口的文本要并进 prompt: " + req.prompt());
        assertEquals("canvas:2:req", req.requestId(), "复用节点上的幂等键，重试不重复建任务");
    }

    @Test
    void missingRequiredPortFailsNodeInsteadOfSubmitting() throws Exception {
        // 测什么：模型要求参考图但一张都没接 → 直接标 FAILED，不提交
        // 怎么算红：照样提交 —— 冻结额度后被提供方拒绝，用户白等一轮还得自己看日志找原因
        gen(2L, "A", "PROCESSING");
        when(nodeMapper.selectById(2L)).thenReturn(nodes.get(0));

        service.submitNodeForJob(2L);

        verify(submitService, never()).submit(any());
        ArgumentCaptor<CanvasNode> patch = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper).updateById(patch.capture());
        assertEquals("FAILED", patch.getValue().getStatus());
        assertTrue(patch.getValue().getErrorMsg().contains("参考图"));
    }

    @Test
    void singleNodeRunOnUnreadyNodeThrowsInsteadOfSilentlyBlocking() {
        // 测什么：手动运行未就绪节点 → 抛出明确原因（显式动作要响亮失败）
        // 怎么算红：静默标 BLOCKED —— 用户点了按钮什么也没发生，无从判断为什么
        asset(1L, "a", "http://cdn/i.png");
        gen(2L, "A", "PENDING");
        gen(3L, "B", "PENDING");
        link("a", "A", "image");
        link("A", "B", "image");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.runNode(USER, CID, "B"));
        assertTrue(ex.getMessage().contains("等待上游"), ex.getMessage());
    }

    @Test
    void optionalPortStillWaitsForUpstream() {
        // 测什么：下游把上游接在「可选」端口上（参考视频，非必填）时，上游没产出照样不能跑
        // 怎么算红：可选口就不等上游了 —— 下游会在缺少参考视频的情况下静默提交，
        //          生成结果和用户画的连线对不上，而钱已经花了。
        //          （这条是「上游必须先产出」这条规则唯一无法被必填口检查兜住的场景）
        when(engine.models()).thenReturn(List.of(new ModelSpec(
                "seedance", "m1", "M1", false, 0, 0, List.of("16:9"), 5, 10,
                List.of(5, 10), OutputType.VIDEO, List.of(), 1, 0, false)));
        gen(2L, "A", "PENDING");
        gen(3L, "B", "PENDING");
        link("A", "B", "video");

        List<CanvasNode> enqueued = service.run(USER, CID);

        assertEquals(1, enqueued.size(), "只有 A 该入队");
        assertEquals("A", enqueued.get(0).getNodeKey());
        verify(asyncJobService, never()).enqueue(eq(CanvasRunServiceImpl.JOB_TYPE),
                eq(CanvasRunServiceImpl.jobKey(CID, 3L)), anyString());
    }

    @Test
    void nonExecutableNodeIsNeverEnqueued() {
        // 测什么：素材/文本节点永不入队
        // 怎么算红：源节点被当成任务提交 —— 白冻结一次钱，且提交内容毫无意义
        asset(1L, "a", "http://cdn/i.png");
        CanvasNode text = new CanvasNode();
        text.setId(9L);
        text.setCanvasId(CID);
        text.setNodeKey("t");
        text.setNodeType("TEXT");
        text.setStatus("IDLE");
        text.setConfig("{\"content\":\"x\"}");
        nodes.add(text);

        List<CanvasNode> enqueued = service.run(USER, CID);

        assertTrue(enqueued.isEmpty());
        verify(asyncJobService, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    void terminalSuccessClearsPreviousFailureReason() {
        // 测什么：节点重试成功后，上一次的失败原因必须被抹掉（写空串，不能写 null）
        // 怎么算红：patch.errorMsg 为 null —— MyBatis-Plus 的 updateById 跳过 null 字段，
        //          等于「这一列不动」，旧的失败原因会原样留在库里；前端状态行是
        //          errorMsg || statusText，于是一个已经成功的节点会一直显示
        //          「Unexpected end of file from server」。2026-08-26 线上真实撞到（canvas_node id=17）。
        CanvasNode a = gen(2L, "A", "PROCESSING");
        a.setTaskId("tsk_A");
        a.setErrorMsg("Unexpected end of file from server");
        when(nodeMapper.selectOne(any())).thenReturn(a);

        service.applyTaskFinished("tsk_A", "SUCCESS", "http://cdn/a.mp4", null);

        ArgumentCaptor<CanvasNode> patches = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeastOnce()).updateById(patches.capture());
        CanvasNode patch = patches.getAllValues().stream()
                .filter(p -> "SUCCESS".equals(p.getStatus()))
                .findFirst().orElseThrow(() -> new AssertionError("没有写入 SUCCESS 的 patch"));
        assertEquals("", patch.getErrorMsg(),
                "成功回填必须显式写空串清掉旧失败原因；写 null 会被 updateById 跳过");
    }

    @Test
    void generatedArtifactIsResolvedToFetchableUrlBeforeSubmit() throws Exception {
        // 测什么：上游是「生成节点」时，它落库的产物是展示用的 key（tsk_xxx.png），
        //        提交前必须换成引擎真能下载的地址
        // 怎么算红：把 key 原样塞进 imageUrls —— ComfyUI 会 downloadBytes("tsk_xxx.png") 直接炸，
        //        报「Unexpected end of file from server」，而钱在提交那一刻已经冻结。
        //        2026-08-26 线上真实撞到（video_task 120~123 连炸四条）
        CanvasNode up = gen(2L, "A", "SUCCESS");
        up.setTaskId("tsk_up");
        up.setOutput("{\"mediaType\":\"IMAGE\",\"url\":\"tsk_up.png\"}");
        CanvasNode down = gen(3L, "B", "PENDING");
        down.setSubmitRequestId("req-B");
        link("A", "B", "image");
        when(nodeMapper.selectById(3L)).thenReturn(down);
        when(artifactResolver.toFetchable(any(), any())).thenReturn(
                new org.example.seedancegenarate.canvas.ResolvedInputs.PortValue(
                        org.example.seedancegenarate.canvas.MediaType.IMAGE,
                        "https://oss/outputs/tsk_up/result.png?sig=x"));
        VideoTask created = new VideoTask();
        created.setBizTaskId("tsk_down");
        when(submitService.submit(any())).thenReturn(created);

        service.submitNodeForJob(3L);

        ArgumentCaptor<VideoSubmitService.SubmitRequest> req =
                ArgumentCaptor.forClass(VideoSubmitService.SubmitRequest.class);
        verify(submitService).submit(req.capture());
        assertEquals(List.of("https://oss/outputs/tsk_up/result.png?sig=x"), req.getValue().imageUrls(),
                "提交给引擎的必须是可下载地址，不是产物 key");
    }

    @Test
    void unresolvableArtifactFailsBeforeSpendingMoney() throws Exception {
        // 测什么：上游产物换不出可下载地址时，节点当场 FAILED 且带原因，绝不提交
        // 怎么算红：照样调 submit —— 冻结了钱去跑一个引擎注定下载不了的输入，
        //        用户四分钟后拿到一句看不懂的网络错误，还得自己去解冻
        CanvasNode up = gen(2L, "A", "SUCCESS");
        up.setTaskId("tsk_up");
        up.setOutput("{\"mediaType\":\"IMAGE\",\"url\":\"tsk_up.png\"}");
        CanvasNode down = gen(3L, "B", "PENDING");
        link("A", "B", "image");
        when(nodeMapper.selectById(3L)).thenReturn(down);
        when(artifactResolver.toFetchable(any(), any()))
                .thenThrow(BusinessException.badRequest("上游节点「A」的产物没有存进对象存储，无法作为下游输入"));

        service.submitNodeForJob(3L);

        verify(submitService, never()).submit(any());
        ArgumentCaptor<CanvasNode> patches = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream().anyMatch(p ->
                        "FAILED".equals(p.getStatus()) && p.getErrorMsg() != null
                                && p.getErrorMsg().contains("无法作为下游输入")),
                "必须标 FAILED 并写明原因");
    }

    @Test
    void reconcileCatchesUpWhenTaskFinishedBeforeTaskIdWasStored() throws Exception {
        // 测什么：任务已经终态、节点却还挂在 PROCESSING 时，对账要把终态补回填上去
        // 怎么算红：对账放着不管 —— 提交是「先建任务、再把 taskId 写回节点」两步，中间任务就可能
        //        已经失败（画布上常常只要 3 秒），那一次终态事件按 task_id 反查不到节点、白发一次，
        //        节点从此永远停在「生成中」，用户既看不到失败原因也没法重试。
        //        2026-08-26 线上真实撞到（canvas_node 17/18 卡 PROCESSING，任务早已 FAILED）
        CanvasNode stuck = gen(2L, "A", "PROCESSING");
        stuck.setTaskId("tsk_A");
        when(nodeMapper.selectOne(any())).thenReturn(stuck);
        VideoTask finished = new VideoTask();
        finished.setBizTaskId("tsk_A");
        finished.setStatus("FAILED");
        finished.setErrorMsg("Unexpected end of file from server");
        when(videoTaskService.getOne(any(), anyBoolean())).thenReturn(finished);

        service.reconcileRunning(CID);

        ArgumentCaptor<CanvasNode> patches = ArgumentCaptor.forClass(CanvasNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream().anyMatch(p ->
                        "FAILED".equals(p.getStatus()) && p.getId().equals(2L)),
                "任务已 FAILED，对账必须把节点也推到 FAILED");
    }

    @Test
    void reconcileNeverEnqueuesPendingNodesOnADraftCanvas() {
        // 测什么：画布不在运行中时，对账绝不给 PENDING 节点补作业
        // 怎么算红：补了 —— 新拖出来的生成节点默认就是 PENDING，用户只是把节点摆上画布、
        //          还没点运行，对账就替他提交了，钱直接扣掉。这是本对账里唯一会花钱的分支，
        //          必须由「画布确实在 RUNNING」这道门看着
        gen(2L, "A", "PENDING");   // 画布默认 DRAFT（见 setUp）

        service.reconcileRunning(CID);

        verify(asyncJobService, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    void reconcileLeavesStillRunningTaskAlone() throws Exception {
        // 测什么：任务确实还在跑时，对账不能动这个节点
        // 怎么算红：把在跑的节点也改掉 —— 用户会看到节点无故变终态，而任务还在烧算力
        CanvasNode running = gen(2L, "A", "PROCESSING");
        running.setTaskId("tsk_A");
        VideoTask inFlight = new VideoTask();
        inFlight.setBizTaskId("tsk_A");
        inFlight.setStatus("PROCESSING");
        when(videoTaskService.getOne(any(), anyBoolean())).thenReturn(inFlight);

        service.reconcileRunning(CID);

        verify(nodeMapper, never()).updateById(any(CanvasNode.class));
    }

    @Test
    void occupyForSubmitClearsErrorMessage() {
        // 测什么：重试提交的原子占位（FAILED → PROCESSING）同时清掉失败原因
        // 怎么算红：占位只改 status —— 作业重试期间节点是「生成中 + 上一次的失败原因」，
        //          用户看到正在跑的节点挂着报错，会以为又炸了而重复点重试
        String sql = org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(
                        org.springframework.util.ReflectionUtils.findMethod(
                                org.example.seedancegenarate.mapper.CanvasNodeMapper.class,
                                "occupyForSubmit", Long.class),
                        org.apache.ibatis.annotations.Update.class)
                .value()[0].replaceAll("\\s+", " ");

        assertTrue(sql.contains("error_msg = NULL"), "占位语句必须一并清 error_msg，实际: " + sql);
        assertTrue(sql.contains("status = 'PROCESSING'") && sql.contains("'PENDING', 'FAILED', 'BLOCKED'"),
                "占位的状态门不能被改动（它同时是防并发双提交的闸），实际: " + sql);
    }

}
