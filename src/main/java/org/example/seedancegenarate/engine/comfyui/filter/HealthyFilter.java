package org.example.seedancegenarate.engine.comfyui.filter;

import org.example.seedancegenarate.engine.comfyui.NodeFilter;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 探测器判定的死活。<b>软</b>条件 —— 全灭时会被放弃。
 * <p>
 * 硬化它的后果：跳板机抖一下、或者探测器自己出了问题，就是整站提交不了。
 * 而放弃它的最坏情况只是提交失败 → {@code markFailed} → 解冻，钱不会错（D-003）。
 * 少发能自愈，全站停摆不能。
 */
@Component
@Order(20)
public class HealthyFilter implements NodeFilter {

    @Override
    public String reject(NodeState node, String model) {
        if (node.healthy()) {
            return null;
        }
        return "探测失败 " + node.consecutiveFailures() + " 次"
                + (node.lastError() == null ? "" : "，最后一次: " + node.lastError());
    }

    @Override
    public boolean hard() {
        return false;
    }
}
