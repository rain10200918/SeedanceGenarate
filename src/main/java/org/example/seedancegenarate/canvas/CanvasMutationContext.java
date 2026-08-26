package org.example.seedancegenarate.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.entity.CanvasNode;

import java.util.List;
import java.util.Map;

/**
 * 一次增量保存「应用之后」的画布快照，交给各 {@link CanvasMutationValidator} 检查。
 * <p>
 * 关键：这是<b>应用后的预期状态</b>而不是当前库里的状态 —— 所有校验必须针对最终结果，
 * 否则「同一批里先删边再连新边」这类合法操作会被误判。
 *
 * @param nodesAfter   应用后的全部节点（nodeKey → 视图）
 * @param edgesAfter   应用后的全部连线
 * @param existingRows 库中现有节点行（查运行态用，如「不能删正在生成的节点」）
 * @param nodeDeletes  本次要删除的 nodeKey
 */
public record CanvasMutationContext(
        Long canvasId,
        Map<String, NodeView> nodesAfter,
        List<EdgeView> edgesAfter,
        Map<String, CanvasNode> existingRows,
        List<String> nodeDeletes) {

    /** 节点的类型与配置：校验器据此向注册表问端口形状 */
    public record NodeView(String nodeKey, String nodeType, JsonNode config) {
    }

    public record EdgeView(String edgeKey, String fromNodeKey, String fromPort,
                           String toNodeKey, String toPort) {
    }
}
