package org.example.seedancegenarate.canvas.type;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.seedancegenarate.canvas.CanvasNodeType;
import org.example.seedancegenarate.canvas.PortSpec;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Layout-only container; membership and adoption never become execution dependencies. */
@Component
public class GroupNodeType implements CanvasNodeType {
    private static final int MAX_MEMBERS = 500;
    private static final int MAX_KEY_LENGTH = 80;
    private static final Set<String> COLORS = Set.of("neutral", "teal", "amber");

    @Override
    public String type() {
        return "GROUP";
    }

    @Override
    public String label() {
        return "分组";
    }

    @Override
    public String description() {
        return "整理节点与结果版本，不参与生成";
    }

    @Override
    public PortSpec ports(JsonNode config) {
        return new PortSpec(null, List.of());
    }

    @Override
    public void validateConfig(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw BusinessException.badRequest("GROUP config 必须是 JSON 对象（空组可使用 {}）");
        }
        JsonNode members = config.path("memberKeys");
        if (!members.isMissingNode()) {
            if (!members.isArray() || members.size() > MAX_MEMBERS) {
                throw BusinessException.badRequest("GROUP memberKeys 必须是数组，最多 " + MAX_MEMBERS + " 项");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < members.size(); i++) {
                String key = validateKey(members.get(i), "memberKeys[" + i + "]");
                if (key.startsWith("result:")) {
                    String source = key.substring("result:".length());
                    if (source.isBlank() || source.startsWith("result:")) {
                        throw BusinessException.badRequest("GROUP memberKeys 镜像必须引用真实节点: " + key);
                    }
                }
                if (!seen.add(key)) {
                    throw BusinessException.badRequest("GROUP memberKeys 不允许重复: " + key);
                }
            }
        }
        JsonNode color = config.path("color");
        if (!color.isMissingNode() && (!color.isTextual() || !COLORS.contains(color.textValue()))) {
            throw BusinessException.badRequest("GROUP color 只支持 neutral、teal、amber");
        }
        JsonNode adopted = config.path("adoptedNodeKey");
        if (!adopted.isMissingNode() && !adopted.isNull()) {
            String key = validateKey(adopted, "adoptedNodeKey");
            if (key.startsWith("result:")) {
                throw BusinessException.badRequest("GROUP adoptedNodeKey 必须使用结果源的真实 nodeKey");
            }
        }
    }

    private String validateKey(JsonNode value, String field) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > MAX_KEY_LENGTH) {
            throw BusinessException.badRequest("GROUP " + field + " 必须是非空字符串，最多 " + MAX_KEY_LENGTH + " 字符");
        }
        return value.textValue();
    }
}
