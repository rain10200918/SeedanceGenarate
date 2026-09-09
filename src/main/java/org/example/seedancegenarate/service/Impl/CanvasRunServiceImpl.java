package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.canvas.SubmitPlan;
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
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 画布执行实现。三件事：<b>算就绪 → 入队 → 终态后推进下游</b>。
 * <p>
 * 不写新调度器：入队走既有 {@code async_job}（行级租约 claim/lease/attempts/backoff），
 * 提交走既有 {@code VideoSubmitService}（冻结/结算/解冻不动）。本类只负责「什么时候该跑谁」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasRunServiceImpl implements CanvasRunService {

    public static final String JOB_TYPE = "CANVAS_NODE_SUBMIT";

    private final CanvasMapper canvasMapper;
    private final CanvasNodeMapper canvasNodeMapper;
    private final CanvasEdgeMapper canvasEdgeMapper;
    private final CanvasNodeTypeRegistry nodeTypeRegistry;
    private final AsyncJobService asyncJobService;
    private final VideoSubmitService videoSubmitService;
    private final CanvasArtifactResolver artifactResolver;
    private final VideoTaskService videoTaskService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public List<CanvasNode> run(Long userId, Long canvasId) {
        Canvas canvas = requireOwned(userId, canvasId);
        List<CanvasNode> nodes = nodesOf(canvasId);
        List<CanvasEdge> edges = edgesOf(canvasId);

        List<CanvasNode> enqueued = new ArrayList<>();
        for (CanvasNode node : nodes) {
            CanvasNodeType type = nodeTypeRegistry.get(node.getNodeType());
            if (!type.executable()) {
                continue;
            }
            // 只跑还没跑过的（PENDING）和失败重跑的（FAILED / BLOCKED）
            if (!isRunnableStatus(node.getStatus())) {
                continue;
            }
            Readiness readiness = checkReadiness(node, nodes, edges);
            if (!readiness.ready()) {
                // 上游没成功不是错误：标 BLOCKED 等上游终态时再推进，且此刻不冻结任何钱
                markBlocked(node, readiness.reason());
                continue;
            }
            enqueueSubmit(node, true);
            enqueued.add(node);
        }

        if (!enqueued.isEmpty() && !"RUNNING".equals(canvas.getStatus())) {
            Canvas patch = new Canvas();
            patch.setId(canvasId);
            patch.setStatus("RUNNING");
            canvasMapper.updateById(patch);
        }
        return enqueued;
    }

    @Override
    @Transactional
    public CanvasNode runNode(Long userId, Long canvasId, String nodeKey) {
        requireOwned(userId, canvasId);
        List<CanvasNode> nodes = nodesOf(canvasId);
        CanvasNode node = nodes.stream()
                .filter(n -> Objects.equals(n.getNodeKey(), nodeKey))
                .findFirst()
                .orElseThrow(() -> BusinessException.badRequest("节点不存在"));

        CanvasNodeType type = nodeTypeRegistry.get(node.getNodeType());
        if (!type.executable()) {
            throw BusinessException.badRequest("该节点不需要运行");
        }
        if ("PROCESSING".equals(node.getStatus())) {
            throw BusinessException.badRequest("该节点正在生成中");
        }
        Readiness readiness = checkReadiness(node, nodes, edgesOf(canvasId));
        if (!readiness.ready()) {
            // 单节点运行是显式动作，未就绪要响亮报错而不是静默标 BLOCKED
            throw BusinessException.badRequest(readiness.reason());
        }
        enqueueSubmit(node, true);
        return node;
    }

    @Override
    public void submitNodeForJob(Long nodeId) throws Exception {
        CanvasNode node = canvasNodeMapper.selectById(nodeId);
        submitNodeForJob(nodeId, node == null ? null : node.getSubmitRequestId());
    }

    @Override
    public void submitNodeForJob(Long nodeId, String expectedRequestId) throws Exception {
        if (!StringUtils.hasText(expectedRequestId)) {
            return;
        }
        CanvasNode node = canvasNodeMapper.selectById(nodeId);
        if (node == null) {
            return;
        }
        if (!expectedRequestId.equals(node.getSubmitRequestId())) {
            return;
        }
        Canvas canvas = canvasMapper.selectById(node.getCanvasId());
        if (canvas == null) {
            return;
        }
        List<CanvasNode> nodes = nodesOf(node.getCanvasId());
        CanvasNodeType type = nodeTypeRegistry.get(node.getNodeType());
        JsonNode config = parse(node.getConfig());

        // 换不出可下载地址就当场失败：submit 会冻结钱，不能拿着引擎下载不了的字符串去提交
        ResolvedInputs inputs;
        try {
            inputs = resolveInputs(node, nodes, edgesOf(node.getCanvasId()), true);
        } catch (BusinessException e) {
            canvasNodeMapper.failIfCurrent(node.getId(), expectedRequestId, truncate(e.getMessage()));
            return;
        }

        String error = type.readinessError(node, config, inputs);
        if (error != null) {
            canvasNodeMapper.failIfCurrent(node.getId(), expectedRequestId, truncate(error));
            return;
        }

        SubmitPlan plan = type.plan(node, config, inputs);
        VideoTask task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                canvas.getUserId(), plan.provider(), plan.model(), plan.prompt(),
                plan.imageUrls(), plan.videoUrls(), plan.audioUrls(),
                plan.duration(), plan.ratio(), plan.megapixels(),
                null, expectedRequestId, null));

        canvasNodeMapper.linkTaskIfMissing(node.getId(), expectedRequestId, task.getBizTaskId());
    }

    @Override
    @Transactional
    public void applyTaskFinished(String taskId, String status, String videoUrl, String errorMsg) {
        applyTaskFinishedInCurrentTransaction(taskId, status, videoUrl, errorMsg);
    }

    private void applyTaskFinishedInCurrentTransaction(String taskId, String status,
                                                       String videoUrl, String errorMsg) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        if (!("SUCCESS".equals(status) || "FAILED".equals(status))) {
            return;
        }
        CanvasNode node = canvasNodeMapper.selectOne(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getTaskId, taskId)
                .last("limit 1"));
        if (node == null) {
            return; // 不是画布的任务（分镜流水或单条生成），本监听器无事可做
        }

        String terminalError = StringUtils.hasText(errorMsg) ? truncate(errorMsg) : "";
        String terminalOutput = null;
        if ("SUCCESS".equals(status) && StringUtils.hasText(videoUrl)) {
            terminalOutput = outputJson(node, videoUrl);
        }
        // 查询与事件回填之间用户可能已 beginRun；taskId+PROCESSING CAS 防旧事件覆盖新代际。
        if (canvasNodeMapper.finishTaskIfCurrent(node.getId(), taskId, status,
                terminalOutput, terminalError) != 1) {
            return;
        }

        node.setStatus(status);
        node.setOutput(terminalOutput == null ? node.getOutput() : terminalOutput);
        node.setErrorMsg(terminalError);
        advanceDownstream(node);
        summarizeCanvas(node.getCanvasId());
    }

    /** 上游终态后：成功则把变就绪的下游入队，失败则把下游标 BLOCKED（不冻结钱） */
    private void advanceDownstream(CanvasNode finished) {
        List<CanvasNode> nodes = nodesOf(finished.getCanvasId());
        List<CanvasEdge> edges = edgesOf(finished.getCanvasId());
        Map<String, CanvasNode> byKey = indexByKey(nodes);

        for (CanvasEdge edge : edges) {
            if (!Objects.equals(edge.getFromNodeKey(), finished.getNodeKey())) {
                continue;
            }
            CanvasNode downstream = byKey.get(edge.getToNodeKey());
            if (downstream == null || !nodeTypeRegistry.get(downstream.getNodeType()).executable()) {
                continue;
            }
            if (!isRunnableStatus(downstream.getStatus())) {
                continue;
            }
            Readiness readiness = checkReadiness(downstream, nodes, edges);
            if (readiness.ready()) {
                enqueueSubmit(downstream, true);
            } else {
                markBlocked(downstream, readiness.reason());
            }
        }
    }

    /** 汇总画布状态：全终态则 DONE / PARTIAL_FAILED，仍有在跑或待跑则保持 RUNNING */
    private void summarizeCanvas(Long canvasId) {
        List<CanvasNode> nodes = nodesOf(canvasId);
        boolean anyActive = false;
        boolean anyFailed = false;
        boolean anyExecutable = false;
        for (CanvasNode n : nodes) {
            if (!nodeTypeRegistry.get(n.getNodeType()).executable()) {
                continue;
            }
            anyExecutable = true;
            if ("PROCESSING".equals(n.getStatus()) || "PENDING".equals(n.getStatus())) {
                anyActive = true;
            }
            if ("FAILED".equals(n.getStatus())) {
                anyFailed = true;
            }
        }
        if (!anyExecutable || anyActive) {
            return;
        }
        Canvas patch = new Canvas();
        patch.setId(canvasId);
        patch.setStatus(anyFailed ? "PARTIAL_FAILED" : "DONE");
        canvasMapper.updateById(patch);
    }

    /**
     * 就绪判定：每条入边的上游必须已产出可用产物。
     * 源节点（素材/文本）配置好就有产物；生成节点必须 SUCCESS 才有。
     */
    private Readiness checkReadiness(CanvasNode node, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        Map<String, CanvasNode> byKey = indexByKey(nodes);
        for (CanvasEdge edge : edges) {
            if (!Objects.equals(edge.getToNodeKey(), node.getNodeKey())) {
                continue;
            }
            CanvasNode upstream = byKey.get(edge.getFromNodeKey());
            if (upstream == null) {
                return new Readiness(false, "上游节点已不存在");
            }
            if ("FAILED".equals(upstream.getStatus())) {
                return new Readiness(false, "上游节点「" + display(upstream) + "」生成失败");
            }
            CanvasNodeType upstreamType = nodeTypeRegistry.get(upstream.getNodeType());
            if (upstreamType.output(upstream, parse(upstream.getConfig())) == null) {
                return new Readiness(false, "等待上游节点「" + display(upstream) + "」产出结果");
            }
        }
        // 必填端口也要检查：连线齐了但端口空着同样不能跑
        ResolvedInputs inputs = resolveInputs(node, nodes, edges);
        String error = nodeTypeRegistry.get(node.getNodeType())
                .readinessError(node, parse(node.getConfig()), inputs);
        return error == null ? new Readiness(true, null) : new Readiness(false, error);
    }

    /** 就绪判定用：只关心「上游有没有产出」，不去碰对象存储 */
    private ResolvedInputs resolveInputs(CanvasNode node, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        return resolveInputs(node, nodes, edges, false);
    }

    /**
     * 把各入边的上游产物按目标端口归拢（同一端口保持连线创建顺序）。
     *
     * @param fetchable true=提交用，把产物换成引擎能下载的地址（见 {@link CanvasArtifactResolver}）；
     *                  false=就绪判定用。分开是因为就绪判定会被 run / 上游终态频繁调用，
     *                  没必要为了「有没有值」去签一堆用不上的地址。
     */
    private ResolvedInputs resolveInputs(CanvasNode node, List<CanvasNode> nodes,
                                         List<CanvasEdge> edges, boolean fetchable) {
        Map<String, CanvasNode> byKey = indexByKey(nodes);
        Map<String, List<ResolvedInputs.PortValue>> byPort = new LinkedHashMap<>();
        for (CanvasEdge edge : edges) {
            if (!Objects.equals(edge.getToNodeKey(), node.getNodeKey())) {
                continue;
            }
            CanvasNode upstream = byKey.get(edge.getFromNodeKey());
            if (upstream == null) {
                continue;
            }
            ResolvedInputs.PortValue value = nodeTypeRegistry.get(upstream.getNodeType())
                    .output(upstream, parse(upstream.getConfig()));
            if (value == null) {
                continue;
            }
            byPort.computeIfAbsent(edge.getToPort(), k -> new ArrayList<>())
                    .add(fetchable ? artifactResolver.toFetchable(upstream, value) : value);
        }
        return new ResolvedInputs(byPort);
    }

    private void enqueueSubmit(CanvasNode node, boolean newRun) {
        String requestId = newRun || !StringUtils.hasText(node.getSubmitRequestId())
                ? "canvas:" + node.getId() + ":" + UUID.randomUUID().toString().replace("-", "")
                : node.getSubmitRequestId();
        boolean startsNewGeneration = newRun || !StringUtils.hasText(node.getSubmitRequestId());
        Boolean enqueued = transactionTemplate.execute(tx -> {
            if (startsNewGeneration
                    && canvasNodeMapper.beginRun(node.getId(), node.getStatus(), requestId) != 1) {
                return false;
            }
            asyncJobService.enqueue(JOB_TYPE, jobKey(node.getCanvasId(), node.getId(), requestId),
                    writeJobPayload(new NodeSubmitPayload(node.getId(), requestId)));
            return true;
        });
        if (!Boolean.TRUE.equals(enqueued)) {
            return;
        }
        node.setSubmitRequestId(requestId);
        node.setStatus("PENDING");
        if (startsNewGeneration) {
            node.setTaskId(null);
            node.setOutput(null);
            node.setErrorMsg(null);
        }
    }

    /** 作业业务幂等键；同一节点的同一轮运行只入队一次 */
    public static String jobKey(Long canvasId, Long nodeId, String requestId) {
        return "canvas:" + canvasId + ":node:" + nodeId + ":request:" + requestId;
    }

    private void markBlocked(CanvasNode node, String reason) {
        CanvasNode patch = new CanvasNode();
        patch.setId(node.getId());
        patch.setStatus("BLOCKED");
        patch.setErrorMsg(truncate(reason));
        canvasNodeMapper.updateById(patch);
    }

    private void markFailed(CanvasNode node, String reason) {
        CanvasNode patch = new CanvasNode();
        patch.setId(node.getId());
        patch.setStatus("FAILED");
        patch.setErrorMsg(truncate(reason));
        canvasNodeMapper.updateById(patch);
    }

    /** 产物类型取该节点声明的输出类型（生图节点存 IMAGE，生视频存 VIDEO） */
    private String outputJson(CanvasNode node, String url) {
        MediaType media = nodeTypeRegistry.get(node.getNodeType())
                .ports(parse(node.getConfig())).output();
        String type = media == null ? "VIDEO" : media.name();
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("mediaType", type);
            payload.put("url", url);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"mediaType\":\"" + type + "\",\"url\":\"" + url + "\"}";
        }
    }

    private String writeJobPayload(NodeSubmitPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化画布提交作业", e);
        }
    }

    private record NodeSubmitPayload(Long canvasNodeId, String expectedRequestId) {
    }

    @Override
    public void reconcileRunning(Long canvasId) {
        Canvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null) {
            return;
        }
        boolean running = "RUNNING".equals(canvas.getStatus());
        for (CanvasNode node : nodesOf(canvasId)) {
            if (!nodeTypeRegistry.get(node.getNodeType()).executable()) {
                continue;
            }
            if ("PROCESSING".equals(node.getStatus()) && StringUtils.hasText(node.getTaskId())) {
                // 画布是什么状态都要补：卡住的节点常常正是把画布状态算错的原因。
                // 注意这一支不是「零副作用」—— 补上的终态会经 advanceDownstream 推进下游、
                // 该提交就提交。那正是丢掉的那次事件本该做的事，不是额外花钱。
                catchUpFinishedTask(node);
            } else if (running && "PENDING".equals(node.getStatus())
                    && (!StringUtils.hasText(node.getSubmitRequestId())
                    || asyncJobService.find(JOB_TYPE,
                    jobKey(canvasId, node.getId(), node.getSubmitRequestId())) == null)) {
                // 补作业会真的提交、真的冻结钱，所以必须先确认画布在运行中：
                // 新拖出来的生成节点默认就是 PENDING，在 DRAFT 画布上补作业 = 用户没点运行就替他花钱
                enqueueSubmit(node, false);
            }
        }
        summarizeCanvas(canvasId);
    }

    /** 任务已经终态但节点没收到回填（taskId 落库晚于任务终态）→ 在这里补上 */
    private void catchUpFinishedTask(CanvasNode node) {
        VideoTask task = videoTaskService.getOne(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getBizTaskId, node.getTaskId())
                .last("limit 1"), false);
        if (task == null || !("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))) {
            return; // 任务还在跑，等事件就行
        }
        log.warn("画布节点补回填：任务已终态但节点仍在生成中 nodeId={} taskId={} status={}",
                node.getId(), node.getTaskId(), task.getStatus());
        // 同类 self-invocation 不经过 @Transactional 代理；显式短事务让“回填 + 推进下游”同成同败。
        transactionTemplate.execute(ignored -> {
            applyTaskFinishedInCurrentTransaction(
                    node.getTaskId(), task.getStatus(), task.getVideoUrl(), task.getErrorMsg());
            return null;
        });
    }

    private boolean isRunnableStatus(String status) {
        return "PENDING".equals(status) || "FAILED".equals(status) || "BLOCKED".equals(status);
    }

    private Map<String, CanvasNode> indexByKey(List<CanvasNode> nodes) {
        Map<String, CanvasNode> map = new HashMap<>();
        nodes.forEach(n -> map.put(n.getNodeKey(), n));
        return map;
    }

    private String display(CanvasNode node) {
        return StringUtils.hasText(node.getTitle()) ? node.getTitle() : node.getNodeKey();
    }

    private JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private List<CanvasNode> nodesOf(Long canvasId) {
        return canvasNodeMapper.selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .orderByAsc(CanvasNode::getId));
    }

    private List<CanvasEdge> edgesOf(Long canvasId) {
        return canvasEdgeMapper.selectList(new LambdaQueryWrapper<CanvasEdge>()
                .eq(CanvasEdge::getCanvasId, canvasId)
                .orderByAsc(CanvasEdge::getId));
    }

    private Canvas requireOwned(Long userId, Long canvasId) {
        Canvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null || !Objects.equals(canvas.getUserId(), userId)) {
            throw BusinessException.badRequest("画布不存在");
        }
        return canvas;
    }

    private record Readiness(boolean ready, String reason) {
    }
}
