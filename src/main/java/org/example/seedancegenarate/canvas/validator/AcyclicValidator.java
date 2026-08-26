package org.example.seedancegenarate.canvas.validator;

import org.example.seedancegenarate.canvas.CanvasMutationContext;
import org.example.seedancegenarate.canvas.CanvasMutationValidator;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无环校验（含自环）。成环会让「所有上游 SUCCESS 才可运行」的就绪判定永远无法满足，
 * 那条画布上的节点会永久卡在待运行，所以必须在保存时挡掉。
 */
@Component
public class AcyclicValidator implements CanvasMutationValidator {

    @Override
    public void validate(CanvasMutationContext ctx) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (CanvasMutationContext.EdgeView e : ctx.edgesAfter()) {
            adjacency.computeIfAbsent(e.fromNodeKey(), k -> new ArrayList<>()).add(e.toNodeKey());
        }
        Set<String> visited = new HashSet<>();
        Set<String> onPath = new HashSet<>();
        for (String start : adjacency.keySet()) {
            if (hasCycle(start, adjacency, visited, onPath)) {
                throw BusinessException.badRequest("连线不能成环");
            }
        }
    }

    private boolean hasCycle(String node, Map<String, List<String>> adjacency,
                             Set<String> visited, Set<String> onPath) {
        if (onPath.contains(node)) return true;
        if (!visited.add(node)) return false;
        onPath.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (hasCycle(next, adjacency, visited, onPath)) return true;
        }
        onPath.remove(node);
        return false;
    }
}
