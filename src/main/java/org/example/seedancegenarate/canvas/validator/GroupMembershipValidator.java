package org.example.seedancegenarate.canvas.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.CanvasNodeTypeRegistry;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Checks layout relationships against the complete post-mutation graph. */
@Component
@RequiredArgsConstructor
public class GroupMembershipValidator implements CanvasMutationValidator {
    private final CanvasNodeTypeRegistry registry;

    @Override
    public void validate(CanvasMutationContext context) {
        Map<String, String> owners = new HashMap<>();
        for (CanvasMutationContext.NodeView group : context.nodesAfter().values()) {
            if (group.nodeKey().startsWith("result:")) {
                throw BusinessException.badRequest("结果镜像不能保存为真实节点: " + group.nodeKey());
            }
            if (!"GROUP".equals(group.nodeType())) continue;

            // Validator bean order is not a precondition; shape validation has one implementation.
            registry.get(group.nodeType()).validateConfig(group.config());
            Set<String> members = new HashSet<>();
            for (JsonNode member : group.config().path("memberKeys")) {
                String key = member.textValue();
                members.add(key);
                boolean mirror = key.startsWith("result:");
                String sourceKey = mirror ? key.substring("result:".length()) : key;
                CanvasMutationContext.NodeView source = context.nodesAfter().get(sourceKey);
                if (source == null) continue; // Deleted members remain harmless layout metadata.
                if ("GROUP".equals(source.nodeType())) {
                    throw BusinessException.badRequest("GROUP " + group.nodeKey()
                            + " 不允许包含组或以组作为镜像来源: " + key);
                }
                if (mirror) {
                    CanvasNodeType type = registry.get(source.nodeType());
                    if (!type.executable() || type.ports(source.config()).output() == null) {
                        throw BusinessException.badRequest("GROUP " + group.nodeKey()
                                + " 的镜像来源必须是可产生结果的生成节点: " + key);
                    }
                    CanvasNode row = context.existingRows().get(sourceKey);
                    // Reuse the strategy's SUCCESS/output semantics; no task state is modified.
                    if (row == null || type.output(row, source.config()) == null) continue;
                }
                String previous = owners.putIfAbsent(key, group.nodeKey());
                if (previous != null) {
                    throw BusinessException.badRequest("可见项 " + key + " 不能同时属于 GROUP "
                            + previous + " 和 " + group.nodeKey());
                }
            }
            String adopted = group.config().path("adoptedNodeKey").asText(null);
            // Deleted adopted sources must not trap old canvases; clearing the marker is optional.
            if (adopted != null && context.nodesAfter().containsKey(adopted)
                    && !members.contains(adopted) && !members.contains("result:" + adopted)) {
                throw BusinessException.badRequest("GROUP " + group.nodeKey()
                        + " 的 adoptedNodeKey 不属于本组: " + adopted);
            }
        }
    }
}
