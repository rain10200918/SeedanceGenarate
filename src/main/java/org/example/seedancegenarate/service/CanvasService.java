package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasEdge;
import org.example.seedancegenarate.entity.CanvasNode;

import java.util.List;

/**
 * 无限画布：独立于分镜流水线的一等功能。
 * <p>
 * 保存一律走 {@link #applyMutation}（按 key 增量 upsert / delete），绝不删旧插新——
 * 节点主键漂移会让连线断裂、在途任务与节点失联。并发用两道：{@code baseVersion} CAS 挡丢失更新，
 * {@code mutationId} 认出重放（响应丢失后客户端重发，不重复应用、返回原结果）。
 */
public interface CanvasService {

    List<Canvas> listCanvases(Long userId);

    Canvas createCanvas(Long userId, String title);

    /** 详情：画布 + 节点（含推导出的端口形状）+ 连线 */
    CanvasDetail getDetail(Long userId, Long canvasId);

    void renameCanvas(Long userId, Long canvasId, String title);

    void deleteCanvas(Long userId, Long canvasId);

    /** 增量保存；冲突抛 409，重放返回首次结果 */
    SaveAck applyMutation(Long userId, Long canvasId, CanvasMutation mutation);

    record CanvasDetail(Canvas canvas, List<NodeView> nodes, List<CanvasEdge> edges) {
    }

    /**
     * 节点 + 它当前的端口形状。端口由服务端按节点配置推导后下发，
     * 前端不必自己复刻推导规则（口径只有一份）。
     */
    record NodeView(CanvasNode node, PortView ports) {
    }

    record PortView(String output, List<PortItem> inputs) {
    }

    record PortItem(String id, String label, String accepts, boolean required, int max) {
    }

    /** 增量保存入参；各列表可为空（全空 = no-op，但仍走 CAS，可用于探测冲突） */
    record CanvasMutation(
            String mutationId,
            Long baseVersion,
            String viewport,
            List<NodeUpsert> nodeUpserts,
            List<String> nodeDeletes,
            List<EdgeUpsert> edgeUpserts,
            List<String> edgeDeletes) {
    }

    /**
     * 节点增量：按 nodeKey 定位。**只描述人能编辑的字段** ——
     * status / taskId / output / submitRequestId / errorMsg 归执行器所有，前端不回传、服务端不覆盖。
     */
    record NodeUpsert(String nodeKey, String nodeType, String title,
                      Integer posX, Integer posY, Integer width, Integer height,
                      String config) {
    }

    record EdgeUpsert(String edgeKey, String fromNodeKey, String fromPort,
                      String toNodeKey, String toPort) {
    }

    /** 保存回执；{@code replayed=true} 表示识别为重放、本次未产生新变更 */
    record SaveAck(String mutationId, long version, boolean replayed) {
    }
}
