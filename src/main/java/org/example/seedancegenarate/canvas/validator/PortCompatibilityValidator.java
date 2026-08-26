package org.example.seedancegenarate.canvas.validator;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.canvas.InputPort;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 连线类型校验：端点必须存在、目标端口必须存在、上游输出类型必须是该端口接受的类型。
 * <p>
 * 这条是画布区别于「无差别 A-B 连线」的核心。把视频接进图片口这种事必须在保存时就拒，
 * 否则错误推迟到提交生成任务时才炸，而那时钱已经冻结了。
 */
@Component
@RequiredArgsConstructor
public class PortCompatibilityValidator implements CanvasMutationValidator {

    private final CanvasNodeTypeRegistry registry;

    @Override
    public void validate(CanvasMutationContext ctx) {
        for (CanvasMutationContext.EdgeView edge : ctx.edgesAfter()) {
            CanvasMutationContext.NodeView from = ctx.nodesAfter().get(edge.fromNodeKey());
            CanvasMutationContext.NodeView to = ctx.nodesAfter().get(edge.toNodeKey());
            if (from == null || to == null) {
                throw BusinessException.badRequest("连线端点不存在: "
                        + edge.fromNodeKey() + " -> " + edge.toNodeKey());
            }

            MediaType produced = registry.get(from.nodeType()).ports(from.config()).output();
            PortSpec targetSpec = registry.get(to.nodeType()).ports(to.config());
            InputPort port = targetSpec.input(edge.toPort());
            if (port == null) {
                throw BusinessException.badRequest("目标节点没有「" + edge.toPort() + "」这个输入端口");
            }
            if (produced == null || produced != port.accepts()) {
                throw BusinessException.badRequest("类型不匹配：上游输出 " + produced
                        + "，「" + port.label() + "」只接受 " + port.accepts());
            }
        }
    }
}
