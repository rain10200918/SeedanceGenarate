package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasEdge;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.CanvasEdgeMapper;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.CanvasService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 画布实现。保存路径固定为三段：<b>全部校验 → CAS → 落库</b>。
 * <p>
 * 顺序不能变：校验全部跑完才动 CAS，所以校验失败时事务里一次写都没发生（不留改了一半的画布）；
 * CAS 通过才落库，所以并发保存不会互相覆盖。
 * <p>
 * 校验规则和节点类型都是可插拔的（{@link CanvasMutationValidator} / {@code CanvasNodeType}），
 * 本类不认识任何具体节点类型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasServiceImpl implements CanvasService {

    /** 终态/进行中的节点：只允许改布局，不许改配置（已提交的任务不会因此变化） */
    private static final Set<String> LAYOUT_ONLY_STATUS = Set.of("PROCESSING", "SUCCESS");
    private static final String DEFAULT_OUT_PORT = "out";

    private final CanvasMapper canvasMapper;
    private final CanvasNodeMapper canvasNodeMapper;
    private final CanvasEdgeMapper canvasEdgeMapper;
    private final CanvasNodeTypeRegistry nodeTypeRegistry;
    private final List<CanvasMutationValidator> validators;
    private final ObjectMapper objectMapper;

    @Override
    public List<Canvas> listCanvases(Long userId) {
        return canvasMapper.selectList(new LambdaQueryWrapper<Canvas>()
                .eq(Canvas::getUserId, userId)
                .orderByDesc(Canvas::getId));
    }

    @Override
    @Transactional
    public Canvas createCanvas(Long userId, String title) {
        Canvas canvas = new Canvas();
        canvas.setUserId(userId);
        canvas.setTitle((title == null || title.isBlank()) ? "未命名画布" : title.trim());
        canvas.setStatus("DRAFT");
        canvas.setVersion(0L);
        canvasMapper.insert(canvas);
        return canvas;
    }

    @Override
    public CanvasDetail getDetail(Long userId, Long canvasId) {
        Canvas canvas = requireOwned(userId, canvasId);
        List<CanvasNode> nodes = nodesOf(canvasId);
        List<CanvasEdge> edges = edgesOf(canvasId);
        List<NodeView> views = new ArrayList<>(nodes.size());
        for (CanvasNode node : nodes) {
            views.add(new NodeView(node, toPortView(node)));
        }
        return new CanvasDetail(canvas, views, edges);
    }

    @Override
    public void renameCanvas(Long userId, Long canvasId, String title) {
        Canvas canvas = requireOwned(userId, canvasId);
        if (!StringUtils.hasText(title)) {
            throw BusinessException.badRequest("画布名不能为空");
        }
        canvas.setTitle(title.trim());
        canvasMapper.updateById(canvas);
    }

    @Override
    @Transactional
    public void deleteCanvas(Long userId, Long canvasId) {
        requireOwned(userId, canvasId);
        canvasNodeMapper.delete(new LambdaQueryWrapper<CanvasNode>().eq(CanvasNode::getCanvasId, canvasId));
        canvasEdgeMapper.delete(new LambdaQueryWrapper<CanvasEdge>().eq(CanvasEdge::getCanvasId, canvasId));
        canvasMapper.deleteById(canvasId);
    }

    @Override
    @Transactional
    public SaveAck applyMutation(Long userId, Long canvasId, CanvasMutation mutation) {
        if (mutation == null || !StringUtils.hasText(mutation.mutationId())) {
            throw BusinessException.badRequest("缺少 mutationId");
        }
        Canvas canvas = requireOwned(userId, canvasId);
        long currentVersion = canvas.getVersion() == null ? 0L : canvas.getVersion();

        // ① 重放识别先于一切：响应丢失后客户端会带同一个 mutationId 重发，此时版本号已推进，
        //    若不先认重放就会误判成冲突。
        if (mutation.mutationId().equals(canvas.getLastMutationId())) {
            return new SaveAck(mutation.mutationId(), currentVersion, true);
        }

        List<CanvasNode> existingNodes = nodesOf(canvasId);
        List<CanvasEdge> existingEdges = edgesOf(canvasId);
        List<NodeUpsert> nodeUpserts = nullSafe(mutation.nodeUpserts());
        List<String> nodeDeletes = nullSafe(mutation.nodeDeletes());
        List<EdgeUpsert> edgeUpserts = nullSafe(mutation.edgeUpserts());
        List<String> edgeDeletes = nullSafe(mutation.edgeDeletes());

        Map<String, CanvasNode> rowsByKey = new HashMap<>();
        existingNodes.forEach(n -> rowsByKey.put(n.getNodeKey(), n));

        // ② 算出「应用之后」的画布，交给校验器链。针对最终状态而非当前状态校验，
        //    否则「同一批里先删边再连新边」这类合法操作会被误判。
        CanvasMutationContext context = buildContext(
                canvasId, rowsByKey, existingEdges, nodeUpserts, nodeDeletes, edgeUpserts, edgeDeletes);
        for (CanvasMutationValidator validator : validators) {
            validator.validate(context);
        }

        // ③ CAS：版本号相符才继续。放在所有写操作之前，冲突时事务里没有任何写。
        long baseVersion = mutation.baseVersion() == null ? currentVersion : mutation.baseVersion();
        int bumped = canvasMapper.bumpVersion(canvasId, baseVersion, mutation.mutationId(), mutation.viewport());
        if (bumped != 1) {
            throw new BusinessException(409, "画布已被其他窗口修改，请刷新后重试");
        }

        // ④ 落库
        for (NodeUpsert upsert : nodeUpserts) {
            CanvasNode existing = rowsByKey.get(upsert.nodeKey());
            if (existing == null) {
                CanvasNode row = new CanvasNode();
                row.setCanvasId(canvasId);
                row.setNodeKey(upsert.nodeKey());
                row.setNodeType(upsert.nodeType());
                // 不可执行的节点（素材/文本）永远是 IDLE，不进就绪扫描
                row.setStatus(nodeTypeRegistry.get(upsert.nodeType()).executable() ? "PENDING" : "IDLE");
                applyEditable(row, upsert, false);
                canvasNodeMapper.insert(row);
            } else {
                boolean layoutOnly = LAYOUT_ONLY_STATUS.contains(existing.getStatus());
                CanvasNode patch = new CanvasNode();
                patch.setId(existing.getId());
                applyEditable(patch, upsert, layoutOnly);
                // patch 里没有 status / taskId / output / submitRequestId / errorMsg ——
                // updateById 只写非空字段，执行器回填的运行态因此永远不会被保存冲掉
                canvasNodeMapper.updateById(patch);
            }
        }
        if (!nodeDeletes.isEmpty()) {
            canvasNodeMapper.delete(new LambdaQueryWrapper<CanvasNode>()
                    .eq(CanvasNode::getCanvasId, canvasId)
                    .in(CanvasNode::getNodeKey, nodeDeletes));
            // 悬空边随节点一起清掉
            canvasEdgeMapper.delete(new LambdaQueryWrapper<CanvasEdge>()
                    .eq(CanvasEdge::getCanvasId, canvasId)
                    .and(w -> w.in(CanvasEdge::getFromNodeKey, nodeDeletes)
                            .or().in(CanvasEdge::getToNodeKey, nodeDeletes)));
        }
        if (!edgeDeletes.isEmpty()) {
            canvasEdgeMapper.delete(new LambdaQueryWrapper<CanvasEdge>()
                    .eq(CanvasEdge::getCanvasId, canvasId)
                    .in(CanvasEdge::getEdgeKey, edgeDeletes));
        }
        Map<String, CanvasEdge> edgeRows = new HashMap<>();
        existingEdges.forEach(e -> edgeRows.put(e.getEdgeKey(), e));
        for (EdgeUpsert upsert : edgeUpserts) {
            CanvasEdge existing = edgeRows.get(upsert.edgeKey());
            if (existing == null) {
                CanvasEdge row = new CanvasEdge();
                row.setCanvasId(canvasId);
                row.setEdgeKey(upsert.edgeKey());
                row.setFromNodeKey(upsert.fromNodeKey());
                row.setFromPort(portOrDefault(upsert.fromPort()));
                row.setToNodeKey(upsert.toNodeKey());
                row.setToPort(upsert.toPort());
                canvasEdgeMapper.insert(row);
            } else {
                CanvasEdge patch = new CanvasEdge();
                patch.setId(existing.getId());
                patch.setFromNodeKey(upsert.fromNodeKey());
                patch.setFromPort(portOrDefault(upsert.fromPort()));
                patch.setToNodeKey(upsert.toNodeKey());
                patch.setToPort(upsert.toPort());
                canvasEdgeMapper.updateById(patch);
            }
        }
        // 刻意不动 canvas.status：画布每拖一下都保存，打回 DRAFT 会不断重置运行态
        return new SaveAck(mutation.mutationId(), baseVersion + 1, false);
    }

    /** 组装「应用之后」的节点与连线视图 */
    private CanvasMutationContext buildContext(Long canvasId,
                                               Map<String, CanvasNode> rowsByKey,
                                               List<CanvasEdge> existingEdges,
                                               List<NodeUpsert> nodeUpserts,
                                               List<String> nodeDeletes,
                                               List<EdgeUpsert> edgeUpserts,
                                               List<String> edgeDeletes) {
        Map<String, CanvasMutationContext.NodeView> nodesAfter = new LinkedHashMap<>();
        for (CanvasNode row : rowsByKey.values()) {
            nodesAfter.put(row.getNodeKey(),
                    new CanvasMutationContext.NodeView(row.getNodeKey(), row.getNodeType(), parse(row.getConfig())));
        }
        for (NodeUpsert upsert : nodeUpserts) {
            if (!StringUtils.hasText(upsert.nodeKey())) {
                throw BusinessException.badRequest("节点缺少 nodeKey");
            }
            if (!StringUtils.hasText(upsert.nodeType())) {
                throw BusinessException.badRequest("节点缺少 nodeType");
            }
            CanvasNode existing = rowsByKey.get(upsert.nodeKey());
            // 已存在且运行中/已完成的节点只改布局，配置沿用库里的
            String config = (existing != null && LAYOUT_ONLY_STATUS.contains(existing.getStatus()))
                    ? existing.getConfig() : upsert.config();
            String type = existing != null ? existing.getNodeType() : upsert.nodeType();
            nodesAfter.put(upsert.nodeKey(),
                    new CanvasMutationContext.NodeView(upsert.nodeKey(), type, parse(config)));
        }
        nodeDeletes.forEach(nodesAfter::remove);

        Map<String, CanvasMutationContext.EdgeView> edgesAfter = new LinkedHashMap<>();
        for (CanvasEdge e : existingEdges) {
            if (edgeDeletes.contains(e.getEdgeKey())) continue;
            // 端点被删的边自动消失（落库时也会连带清理）
            if (!nodesAfter.containsKey(e.getFromNodeKey()) || !nodesAfter.containsKey(e.getToNodeKey())) continue;
            edgesAfter.put(e.getEdgeKey(), new CanvasMutationContext.EdgeView(
                    e.getEdgeKey(), e.getFromNodeKey(), e.getFromPort(), e.getToNodeKey(), e.getToPort()));
        }
        for (EdgeUpsert upsert : edgeUpserts) {
            if (!StringUtils.hasText(upsert.edgeKey())) {
                throw BusinessException.badRequest("连线缺少 edgeKey");
            }
            if (!StringUtils.hasText(upsert.toPort())) {
                throw BusinessException.badRequest("连线缺少目标端口");
            }
            edgesAfter.put(upsert.edgeKey(), new CanvasMutationContext.EdgeView(
                    upsert.edgeKey(), upsert.fromNodeKey(), portOrDefault(upsert.fromPort()),
                    upsert.toNodeKey(), upsert.toPort()));
        }

        return new CanvasMutationContext(canvasId, nodesAfter,
                List.copyOf(edgesAfter.values()), rowsByKey, nodeDeletes);
    }

    /** 只搬「人能编辑的字段」；layoutOnly=true 时连配置也不搬，仅布局 */
    private void applyEditable(CanvasNode target, NodeUpsert upsert, boolean layoutOnly) {
        target.setPosX(upsert.posX());
        target.setPosY(upsert.posY());
        target.setWidth(upsert.width());
        target.setHeight(upsert.height());
        target.setTitle(upsert.title());
        if (!layoutOnly) {
            target.setConfig(upsert.config());
        }
    }

    private PortView toPortView(CanvasNode node) {
        PortSpec spec = nodeTypeRegistry.get(node.getNodeType()).ports(parse(node.getConfig()));
        List<PortItem> inputs = spec.inputs().stream()
                .map(p -> new PortItem(p.id(), p.label(), p.accepts().name(), p.required(), p.max()))
                .toList();
        return new PortView(spec.output() == null ? null : spec.output().name(), inputs);
    }

    private JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // 坏 JSON 不能让整块画布打不开：当作未配置，校验器会按缺配置报错
            log.warn("画布节点配置解析失败，按未配置处理: {}", e.getMessage());
            return null;
        }
    }

    private String portOrDefault(String port) {
        return StringUtils.hasText(port) ? port : DEFAULT_OUT_PORT;
    }

    private List<CanvasNode> nodesOf(Long canvasId) {
        return canvasNodeMapper.selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .orderByAsc(CanvasNode::getId));
    }

    private List<CanvasEdge> edgesOf(Long canvasId) {
        return canvasEdgeMapper.selectList(new LambdaQueryWrapper<CanvasEdge>()
                .eq(CanvasEdge::getCanvasId, canvasId));
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private Canvas requireOwned(Long userId, Long canvasId) {
        Canvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null || !Objects.equals(canvas.getUserId(), userId)) {
            throw BusinessException.badRequest("画布不存在");
        }
        return canvas;
    }
}
