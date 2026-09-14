package org.example.seedancegenarate.canvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.canvas.type.GroupNodeType;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GroupNodeTypeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GroupNodeType group = new GroupNodeType();

    @Test
    void groupHasNoPortsExecutionOrOutput() {
        // 【测什么】GROUP 不暴露端口、不执行，即使错误携带旧产物也不向下游提供。
        // 【怎么算红】让 GROUP executable 返回 true 或提供任一端口/产物，此测试必须失败。
        assertEquals("GROUP", group.type());
        assertFalse(group.executable());
        assertNull(group.ports(null).output());
        assertTrue(group.ports(null).inputs().isEmpty());
        CanvasNode row = new CanvasNode();
        row.setStatus("SUCCESS");
        row.setOutput("{\"mediaType\":\"VIDEO\",\"url\":\"artifact-key\"}");
        assertNull(group.output(row, json.createObjectNode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"memberKeys\":[]}", "{\"adoptedNodeKey\":null}",
            "{\"color\":\"neutral\"}", "{\"color\":\"teal\"}", "{\"color\":\"amber\"}",
            "{\"memberKeys\":[\"n\",\"result:n\"],\"adoptedNodeKey\":\"n\"}"})
    void acceptsEmptyAndContractShapes(String raw) throws Exception {
        // 【测什么】空组、缺省成员、三色及真实 key 的采用标记符合契约。
        // 【怎么算红】把 memberKeys 改为必填或拒绝合法颜色/采用 null，对应参数必须失败。
        assertDoesNotThrow(() -> group.validateConfig(json.readTree(raw)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "[]", "1", "true", "\"x\"", "{\"memberKeys\":null}",
            "{\"memberKeys\":{}}", "{\"memberKeys\":[1]}", "{\"memberKeys\":[null]}",
            "{\"memberKeys\":[\"\"]}", "{\"memberKeys\":[\"  \"]}",
            "{\"memberKeys\":[\"n\",\"n\"]}", "{\"memberKeys\":[\"result:\"]}",
            "{\"memberKeys\":[\"result:result:n\"]}", "{\"color\":\"pink\"}",
            "{\"color\":1}", "{\"color\":null}", "{\"adoptedNodeKey\":1}",
            "{\"adoptedNodeKey\":{}}", "{\"adoptedNodeKey\":\" \"}",
            "{\"adoptedNodeKey\":\"result:n\"}"})
    void rejectsMalformedShapesWithSpecific400(String raw) throws Exception {
        // 【测什么】畸形对象、数组项、重复、镜像键、颜色和采用字段均给具体 400。
        // 【怎么算红】删掉对应 GroupNodeType 字段守卫，该参数不再抛 400 必须变红。
        var config = raw == null ? null : json.readTree(raw);
        BusinessException ex = assertThrows(BusinessException.class, () -> group.validateConfig(config));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("GROUP"), ex.getMessage());
    }

    @Test
    void enforcesExactMemberAndKeyLimitsWithoutChangingConfig() {
        // 【测什么】500 项和 80 字符恰好可存，501 项/81 字符拒绝且不改用户 JSON。
        // 【怎么算红】把上限判断改为 >= 或删上限，边界放行/拒绝断言必须失败。
        var config = json.createObjectNode();
        var members = config.putArray("memberKeys");
        for (int i = 0; i < 500; i++) members.add("member-" + i);
        var before = config.deepCopy();
        assertDoesNotThrow(() -> group.validateConfig(config));
        assertEquals(before, config);
        members.add("overflow");
        assertEquals(400, assertThrows(BusinessException.class, () -> group.validateConfig(config)).getCode());
        members.removeAll().add("x".repeat(80));
        config.put("adoptedNodeKey", "x".repeat(80));
        assertDoesNotThrow(() -> group.validateConfig(config));
        config.put("adoptedNodeKey", "x".repeat(81));
        assertThrows(BusinessException.class, () -> group.validateConfig(config));
        config.remove("adoptedNodeKey");
        members.removeAll().add("x".repeat(81));
        assertThrows(BusinessException.class, () -> group.validateConfig(config));
    }
}
