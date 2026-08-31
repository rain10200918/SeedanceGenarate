package org.example.seedancegenarate.engine.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个模型跑起来需要什么：<b>哪些 node type</b>（从工作流模板算出来）+ <b>多少显存</b>（配置）。
 *
 * <h3>为什么是算出来的而不是人维护的</h3>
 * 人维护的能力表第一天就会过期 —— 改一次工作流模板，表就错了，而且不报错。
 * 这里在启动时把 {@code comfyui/workflows/*.json} 里所有 {@code class_type} 抓出来，
 * 模板改了，需求自动跟着变。
 *
 * <h3>「不知道」和「不需要」必须分开</h3>
 * 模板解析失败（文件缺失 / JSON 坏了）时返回 <b>null</b>，调用方据此<b>跳过能力过滤</b>，
 * 退回到「不做检查」的既有行为。返回空集会被当成「这个工作流什么都不需要」——
 * 那倒是能通过所有过滤，但会让一次解析失败悄悄地把整个防护关掉且没人知道。
 * 所以解析失败要打 ERROR，而不是静默返回空集。
 */
@Slf4j
@Component
public class WorkflowRequirements {

    private final List<WorkflowBuilder> builders;
    private final ComfyUiProperties properties;
    private final ObjectMapper objectMapper;

    /** model → 该工作流用到的全部 class_type。缺席 = 解析失败 = 不做能力过滤 */
    private Map<String, Set<String>> nodeTypes = Map.of();

    public WorkflowRequirements(List<WorkflowBuilder> builders, ComfyUiProperties properties,
                                ObjectMapper objectMapper) {
        this.builders = builders;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void parseTemplates() {
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (WorkflowBuilder builder : builders) {
            try (InputStream in = new ClassPathResource(builder.templatePath()).getInputStream()) {
                Set<String> types = new LinkedHashSet<>();
                collectClassTypes(objectMapper.readTree(in), types);
                if (types.isEmpty()) {
                    log.error("ComfyUI 工作流模板里一个 class_type 都没有，能力过滤对该模型失效: {} ({})",
                            builder.model(), builder.templatePath());
                    continue;
                }
                parsed.put(builder.model(), Set.copyOf(types));
            } catch (Exception e) {
                log.error("ComfyUI 工作流模板解析失败，能力过滤对该模型失效: {} ({}) - {}",
                        builder.model(), builder.templatePath(), e.getMessage());
            }
        }
        this.nodeTypes = Map.copyOf(parsed);
        log.info("ComfyUI 工作流需求已解析: {} 个模型，node type 并集 {} 种",
                parsed.size(), parsed.values().stream().flatMap(Set::stream).distinct().count());
    }

    /**
     * 递归抓 {@code class_type}，而不是只看顶层的 {@code {"1": {...}, "2": {...}}}。
     * <p>
     * API 格式的工作流绝大多数是扁平的，但部分模板带子图 / 嵌套结构；
     * 只扫顶层会漏掉子图里的节点类型，于是一台缺着子图插件的机器仍然被判成「能跑」，
     * 提交过去才报 missing_node_type —— 那正是这套过滤要消灭的失败模式。
     */
    private static void collectClassTypes(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            JsonNode type = node.get("class_type");
            if (type != null && type.isTextual() && !type.asText().isBlank()) {
                out.add(type.asText());
            }
            node.fields().forEachRemaining(e -> collectClassTypes(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectClassTypes(child, out));
        }
    }

    /** 这个模型需要哪些 node type；<b>null = 不知道</b>（模板没解析出来），调用方应跳过能力过滤 */
    public Set<String> nodeTypesFor(String model) {
        return model == null ? null : nodeTypes.get(model);
    }

    /** 这个模型至少要多少显存（字节）；<b>null = 没配</b>，调用方应跳过显存过滤 */
    public Long minVramBytesFor(String model) {
        if (model == null) {
            return null;
        }
        Double gib = properties.getModelMinVramGib().get(model);
        return gib == null ? null : (long) (gib * 1024 * 1024 * 1024);
    }

    /** 管理端用：这台节点能跑哪几个模型。能力未知时返回全部（和过滤链的判断保持一致） */
    public List<String> runnableModelsOn(NodeState node) {
        return builders.stream()
                .map(WorkflowBuilder::model)
                .filter(model -> missingNodeTypesOn(node, model).isEmpty()
                        && vramShortfallOn(node, model) == null)
                .toList();
    }

    /** 这台节点缺哪些 node type 才跑不了这个模型。空 = 跑得了（含「不知道所以不拦」） */
    public Set<String> missingNodeTypesOn(NodeState node, String model) {
        Set<String> required = nodeTypesFor(model);
        Set<String> available = node.capabilities();
        if (required == null || available == null) {
            return Set.of(); // 任一侧未知 → 不拦
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(available);
        return missing;
    }

    /** 显存差多少字节；null = 够用或不知道 */
    public Long vramShortfallOn(NodeState node, String model) {
        Long need = minVramBytesFor(model);
        Long have = node.vramTotal();
        if (need == null || have == null || have >= need) {
            return null;
        }
        return need - have;
    }
}
