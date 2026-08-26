package org.example.seedancegenarate.canvas;

import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布节点类型注册表。Spring 自动注入所有 {@link CanvasNodeType} 实现并按 type 建索引。
 * 新增节点类型无需改这里，也无需改 CanvasService —— 与 {@code VideoEngineRegistry} 同构。
 */
@Component
public class CanvasNodeTypeRegistry {

    private final Map<String, CanvasNodeType> types = new LinkedHashMap<>();

    public CanvasNodeTypeRegistry(List<CanvasNodeType> implementations) {
        for (CanvasNodeType t : implementations) {
            types.put(t.type(), t);
        }
    }

    public CanvasNodeType get(String type) {
        CanvasNodeType found = types.get(type);
        if (found == null) {
            throw BusinessException.badRequest("不支持的画布节点类型: " + type);
        }
        return found;
    }

    /** 全部已注册类型，供 GET /api/canvas/node-types 下发前端渲染节点面板 */
    public Collection<CanvasNodeType> all() {
        return types.values();
    }
}
