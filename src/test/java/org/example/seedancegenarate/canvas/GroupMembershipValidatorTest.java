package org.example.seedancegenarate.canvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.canvas.type.AssetNodeType;
import org.example.seedancegenarate.canvas.type.GenerateNodeType;
import org.example.seedancegenarate.canvas.type.GroupNodeType;
import org.example.seedancegenarate.canvas.type.TextNodeType;
import org.example.seedancegenarate.canvas.validator.GroupMembershipValidator;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GroupMembershipValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CanvasNodeTypeRegistry registry = new CanvasNodeTypeRegistry(List.of(
            new GroupNodeType(), new TextNodeType(), new AssetNodeType(),
            new GenerateNodeType(mock(VideoEngineRegistry.class))));
    private final GroupMembershipValidator validator = new GroupMembershipValidator(registry);

    private CanvasMutationContext.NodeView node(String key, String type, String config) throws Exception {
        return new CanvasMutationContext.NodeView(key, type, json.readTree(config));
    }

    private CanvasMutationContext context(Map<String, CanvasNode> existing,
            CanvasMutationContext.NodeView... nodes) {
        var after = new LinkedHashMap<String, CanvasMutationContext.NodeView>();
        for (var node : nodes) after.put(node.nodeKey(), node);
        return new CanvasMutationContext(1L, after, List.of(), existing, List.of());
    }

    private CanvasNode produced() {
        CanvasNode row = new CanvasNode();
        row.setNodeKey("n");
        row.setNodeType("GENERATE");
        row.setStatus("SUCCESS");
        row.setOutput("{\"mediaType\":\"VIDEO\",\"url\":\"artifact-key\"}");
        return row;
    }

    @ParameterizedTest
    @ValueSource(strings = {"g", "other", "result:g", "result:other"})
    void rejectsDirectAndMirroredGroupsIncludingSelf(String member) throws Exception {
        // 【测什么】自身/其他 GROUP 都不能成为直接成员或镜像来源。
        // 【怎么算红】删掉源类型 GROUP 拒绝分支，四个参数至少一个必须变红。
        var ctx = context(Map.of(), node("g", "GROUP", "{\"memberKeys\":[\"" + member + "\"]}"),
                node("other", "GROUP", "{}"));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(member), ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEXT", "ASSET"})
    void existingMirrorSourceMustGenerateResultsEvenWhenItHasNoOutput(String type) throws Exception {
        // 【测什么】已存在的非生成节点不能冒充结果来源，不能用“暂无产物”绕过类型检查。
        // 【怎么算红】在类型检查前直接按 output 缺失忽略，TEXT/ASSET 镜像必须漏检变红。
        var ctx = context(Map.of(), node("n", type, "{}"),
                node("g", "GROUP", "{\"memberKeys\":[\"result:n\"]}"));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("result:n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "result:n"})
    void sameVisibleItemCannotBelongToTwoGroups(String member) throws Exception {
        // 【测什么】现存真实节点及已产出的镜像各自只能属于一组。
        // 【怎么算红】不记录/不检查跨组 owner，重复归属必须不再报错而变红。
        String config = "{\"memberKeys\":[\"" + member + "\"]}";
        var ctx = context(Map.of("n", produced()), node("n", "GENERATE", "{}"),
                node("g1", "GROUP", config), node("g2", "GROUP", config));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(member));
        assertTrue(ex.getMessage().contains("g1") && ex.getMessage().contains("g2"));
    }

    @Test
    void sourceAndItsMirrorAreDifferentVisibleItems() throws Exception {
        // 【测什么】真实节点和结果镜像是两个可见项，分别归组不会被错误合并。
        // 【怎么算红】把 owner 的 key 统一去掉 result: 前缀，合法分组必须失败。
        var ctx = context(Map.of("n", produced()), node("n", "GENERATE", "{}"),
                node("g1", "GROUP", "{\"memberKeys\":[\"n\"]}"),
                node("g2", "GROUP", "{\"memberKeys\":[\"result:n\"],\"adoptedNodeKey\":\"n\"}"));
        assertDoesNotThrow(() -> validator.validate(ctx));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "result:n"})
    void adoptionMatchesDirectMemberOrMirrorByRealKey(String member) throws Exception {
        // 【测什么】adoptedNodeKey 使用真实 key，并可通过真实成员或 result 成员证明归属。
        // 【怎么算红】只判断 adopted 的直接成员关系，result:n 参数必须失败。
        var ctx = context(Map.of(), node("n", "GENERATE", "{}"),
                node("g", "GROUP", "{\"memberKeys\":[\"" + member + "\"],\"adoptedNodeKey\":\"n\"}"));
        assertDoesNotThrow(() -> validator.validate(ctx));
    }

    @Test
    void missingMembersAndDeletedAdoptionAreIgnoredAgainstFinalGraph() throws Exception {
        // 【测什么】删除后的成员和采用源即使 existingRows 仍有行，也按最终态视失效。
        // 【怎么算红】用 existingRows 判断采用源仍存在，会误拒绝此旧图继续保存。
        var ctx = context(Map.of("n", produced()),
                node("g1", "GROUP", "{\"memberKeys\":[\"gone\",\"result:n\"],\"adoptedNodeKey\":\"n\"}"),
                node("g2", "GROUP", "{\"memberKeys\":[\"gone\",\"result:n\"]}"));
        var before = ctx.nodesAfter().get("g1").config().deepCopy();
        assertDoesNotThrow(() -> validator.validate(ctx));
        assertEquals(before, ctx.nodesAfter().get("g1").config());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "broken-json", "{\"url\":\"\"}",
            "{\"url\":\"artifact-key\",\"mediaType\":\"INVALID\"}"})
    void unproducedOrInvalidOutputMakesMirrorStaleWithoutBlockingSave(String output) throws Exception {
        // 【测什么】已有生成源无可用产物时镜像按失效项忽略，旧组不因重复残留卡住保存。
        // 【怎么算红】无条件把 result:n 加入 owner 或直接解析坏 output 抛异常，此测试必须失败。
        CanvasNode row = produced();
        row.setOutput("null".equals(output) ? null : output);
        String config = "{\"memberKeys\":[\"result:n\"],\"adoptedNodeKey\":\"n\"}";
        var ctx = context(Map.of("n", row), node("n", "GENERATE", "{}"),
                node("g1", "GROUP", config), node("g2", "GROUP", config));
        assertDoesNotThrow(() -> validator.validate(ctx));
    }

    @Test
    void unfinishedSourceDoesNotExposeItsOldOutputAsLiveMirror() throws Exception {
        // 【测什么】运行中的源即使残存旧 output，也不把镜像当成当前可见结果。
        // 【怎么算红】只检查 output 字符串非空而不复用类型的 output 判断，重复旧镜像必须误报。
        CanvasNode row = produced();
        row.setStatus("PROCESSING");
        String config = "{\"memberKeys\":[\"result:n\"]}";
        var ctx = context(Map.of("n", row), node("n", "GENERATE", "{}"),
                node("g1", "GROUP", config), node("g2", "GROUP", config));
        assertDoesNotThrow(() -> validator.validate(ctx));
    }

    @Test
    void mirrorCannotBecomeAPersistedNode() throws Exception {
        // 【测什么】result: 仅表示展示镜像，不能作为真实 node_key 存储。
        // 【怎么算红】删除真实节点 key 的镜像前缀守卫，伪造镜像节点必须漏检变红。
        var ctx = context(Map.of(), node("result:n", "TEXT", "{}"));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("result:n"));
    }

    @Test
    void malformedConfigIs400RegardlessOfValidatorOrdering() throws Exception {
        // 【测什么】GROUP 关系校验先执行时仍通过同一策略检形状，畸形输入不会 500。
        // 【怎么算红】直接遍历未经形状校验的配置，标量不报错或 null 崩溃必须变红。
        var ctx = context(Map.of(), node("g", "GROUP", "1"));
        assertEquals(400, assertThrows(BusinessException.class, () -> validator.validate(ctx)).getCode());
    }

    @Test
    void adoptionOutsideGroupIsRejected() throws Exception {
        // 【测什么】存在但不在本组的节点不能被采用。
        // 【怎么算红】删除 adopted 归属判断，非成员采用必须不再抛 400 而变红。
        var ctx = context(Map.of(), node("n", "GENERATE", "{}"),
                node("g", "GROUP", "{\"adoptedNodeKey\":\"n\"}"));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(ctx));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("adoptedNodeKey"));
    }
}
