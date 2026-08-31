package org.example.seedancegenarate.dto;

import java.util.List;
import java.util.Map;

/**
 * 单个 ComfyUI 节点在管理端的样子。数据源是<b>后台探测器的内存快照</b>，不是现场发请求 ——
 * 打开页面是零 IO 的，而且看到的和调度器此刻用来派活的是<b>同一份数据</b>。
 * <p>
 * 从前这里每次都现场并行探测全部节点：页面上看到的和调度看到的是两次不同的观测，
 * 「页面显示正常但活派不过去」这类问题因此查不清。
 *
 * @param enabled        人的意愿（管理端开关）
 * @param archived       已退役。行和快照都留着，只是不探不派、列表默认不显示
 * @param online         机器的状态（探测器判定）。和 enabled 是两件事，不能合并
 * @param weight         相对算力，H100 = 1.0
 * @param pending        已派发、对方队列还没反映出来的数量
 * @param nodeTypeCount  /object_info 里的 node type 总数；null = 还没探到
 * @param runnableModels 这台机器能跑的模型（能力 ∩ 显存都满足的）
 * @param versions       comfyui / torch / python 版本，用于发现版本漂移
 */
public record NodeHealth(
        String id,
        String baseUrl,
        boolean enabled,
        boolean archived,
        String remark,
        boolean online,
        double weight,
        long latencyMs,
        int queueLoad,
        int pending,
        String gpuName,
        Long vramTotal,
        Long vramFree,
        Integer nodeTypeCount,
        List<String> runnableModels,
        Map<String, String> versions,
        String error
) {
}
