package org.example.seedancegenarate.service;

import org.example.seedancegenarate.dto.NodeHealth;

import java.util.List;

/** ComfyUI 节点健康检测：并行探测全部启用节点，单节点失败不拖累整体 */
public interface NodeHealthService {

    List<NodeHealth> checkAll();
}
