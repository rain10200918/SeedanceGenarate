package org.example.seedancegenarate.engine.comfyui.filter;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.comfyui.NodeFilter;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.example.seedancegenarate.engine.comfyui.WorkflowRequirements;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 显存够不够装下这个模型。
 *
 * <h3>看 vram_total，不看 vram_free</h3>
 * {@code vram_free} 是<b>此刻</b>的空闲量，它随着别的任务跑完就涨回来 ——
 * 按它过滤等于「这台现在忙，所以它永远跑不了 minimax」，把一个排队问题误判成能力问题。
 * 而 {@code vram_total} 是这台机器的物理上限：32 GiB 的 5090D 无论多空闲都装不下
 * 需要 53 GiB 的链路，文件给全了也一样。
 *
 * <h3>没配就不拦</h3>
 * {@code video.comfyui.model-min-vram-gib} 里没写的模型一律放行。
 * 宁可漏拦（退回今天的行为：提交过去 OOM 失败 → 解冻），
 * 也不能因为运维忘了填一个数就把所有节点都判成跑不了。
 */
@Component
@Order(40)
@RequiredArgsConstructor
public class VramFilter implements NodeFilter {

    private static final double GIB = 1024.0 * 1024 * 1024;

    private final WorkflowRequirements requirements;

    @Override
    public String reject(NodeState node, String model) {
        Long shortfall = requirements.vramShortfallOn(node, model);
        if (shortfall == null) {
            return null;
        }
        Long need = requirements.minVramBytesFor(model);
        return String.format("显存不足: 需要 %.1f GiB，只有 %.1f GiB",
                need / GIB, node.vramTotal() / GIB);
    }
}
