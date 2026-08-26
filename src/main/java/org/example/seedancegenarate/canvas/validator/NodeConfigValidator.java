package org.example.seedancegenarate.canvas.validator;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.springframework.stereotype.Component;

/**
 * 把配置校验委托给节点类型自己（策略模式的落点）：素材节点要求选了素材、文本节点限长、
 * 生成节点要求模型可用……新增节点类型自带校验，本类不用改。
 */
@Component
@RequiredArgsConstructor
public class NodeConfigValidator implements CanvasMutationValidator {

    private final CanvasNodeTypeRegistry registry;

    @Override
    public void validate(CanvasMutationContext ctx) {
        for (CanvasMutationContext.NodeView node : ctx.nodesAfter().values()) {
            registry.get(node.nodeType()).validateConfig(node.config());
        }
    }
}
