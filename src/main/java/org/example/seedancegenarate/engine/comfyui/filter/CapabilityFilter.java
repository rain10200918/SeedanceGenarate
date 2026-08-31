package org.example.seedancegenarate.engine.comfyui.filter;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.comfyui.NodeFilter;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.example.seedancegenarate.engine.comfyui.WorkflowRequirements;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 这台机器装没装齐这个工作流用到的 node type。
 *
 * <h3>验收标准是 node type 的集合，不是文件列表</h3>
 * 文件对了不代表能跑：Spark 上 8 个插件目录 rsync 全了，还是因为缺 {@code torchsde}
 * 起不来，{@code /object_info} 里那些 node type 就是不出现。而 node type 一旦出现，
 * 就一定能跑（2026-08-28 实测：893 → 1487 种，7 个工作流全绿）。
 *
 * <h3>「不知道」不拦</h3>
 * 节点侧能力未知（还没探到 / {@code /object_info} 拉失败）或工作流侧需求未知（模板没解析出来）时
 * 一律放行，退回没有这道过滤的行为。把「未知」当成「不满足」的话，
 * 一次 {@code /object_info} 超时就等于把整台机器摘了。
 * 判断逻辑在 {@link WorkflowRequirements#missingNodeTypesOn} 里，只有那一份。
 */
@Component
@Order(30)
@RequiredArgsConstructor
public class CapabilityFilter implements NodeFilter {

    private final WorkflowRequirements requirements;

    @Override
    public String reject(NodeState node, String model) {
        Set<String> missing = requirements.missingNodeTypesOn(node, model);
        if (missing.isEmpty()) {
            return null;
        }
        return "缺 node type: " + missing.stream().limit(5).toList()
                + (missing.size() > 5 ? " 等 " + missing.size() + " 种" : "");
    }
}
