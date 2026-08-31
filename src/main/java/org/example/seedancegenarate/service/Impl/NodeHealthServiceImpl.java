package org.example.seedancegenarate.service.Impl;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.engine.comfyui.ComfyUiFleet;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.example.seedancegenarate.engine.comfyui.WorkflowRequirements;
import org.example.seedancegenarate.service.NodeHealthService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 节点健康：直接读后台探测器的<b>内存快照</b>，一次 HTTP 都不发。
 *
 * <h3>为什么不再现场探测</h3>
 * 从前每次调用都并行探测全部节点（{@code /queue} 几 MB × N 台）。两个问题：
 * <ul>
 *   <li>页面上看到的和<b>调度此刻用来派活的</b>是两次不同的观测。
 *       「页面显示一切正常，但活就是派不过去」这类问题因此查不清 ——
 *       而这正是最需要这个页面的时候</li>
 *   <li>谁点一下刷新，就往机队打一轮请求。看板做自动刷新就是一台放大器</li>
 * </ul>
 * 代价是数据最多旧 3 秒（快探测周期）。看板本来就不需要更新。
 */
@Service
@RequiredArgsConstructor
public class NodeHealthServiceImpl implements NodeHealthService {

    private final ComfyUiFleet fleet;
    private final WorkflowRequirements requirements;

    @Override
    public List<NodeHealth> checkAll() {
        return fleet.nodes().stream().map(this::toHealth).toList();
    }

    private NodeHealth toHealth(NodeState node) {
        return new NodeHealth(
                node.id(),
                node.baseUrl(),
                node.enabled(),
                node.archived(),
                node.remark(),
                node.healthy(),
                node.weight(),
                node.probeLatencyMs(),
                node.queueDepth(),
                fleet.pendingCount(node.id()),
                node.gpuName(),
                node.vramTotal(),
                // vram_free 刻意不透出：它是此刻的空闲量，随别的任务跑完就涨回来。
                // 放在看板上会诱导人拿它做容量判断，而路由用的是 vram_total（物理上限）。
                null,
                node.capabilities() == null ? null : node.capabilities().size(),
                requirements.runnableModelsOn(node),
                node.versions(),
                node.lastError());
    }
}
