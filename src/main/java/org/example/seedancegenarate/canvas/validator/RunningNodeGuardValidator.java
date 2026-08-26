package org.example.seedancegenarate.canvas.validator;

import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 运行中节点保护：正在生成的节点不能删。
 * 删了在途任务就成孤儿——终态回填找不到节点，钱冻结了却无处展示。
 */
@Component
public class RunningNodeGuardValidator implements CanvasMutationValidator {

    @Override
    public void validate(CanvasMutationContext ctx) {
        for (String key : ctx.nodeDeletes()) {
            CanvasNode row = ctx.existingRows().get(key);
            if (row != null && "PROCESSING".equals(row.getStatus())) {
                throw BusinessException.badRequest("节点正在生成中，不能删除：" + key);
            }
        }
    }
}
