package org.example.seedancegenarate.engine.comfyui.filter;

import org.example.seedancegenarate.engine.comfyui.NodeFilter;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 人工开关。排在最前面，因为它最便宜且淘汰得最干脆 ——
 * 后面几道要算集合差、比显存，没必要为一台人已经关掉的机器算。
 * <p>
 * 这是<b>硬</b>条件：运维关掉一台去检修，不能因为「其它节点都不健康」就把活派回去。
 */
@Component
@Order(10)
public class EnabledFilter implements NodeFilter {

    @Override
    public String reject(NodeState node, String model) {
        if (node.archived()) {
            return "已归档（退役）";
        }
        return node.enabled() ? null : "未启用（人工关闭）";
    }
}
