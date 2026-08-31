package org.example.seedancegenarate.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.engine.comfyui.ComfyNodeRegistry;
import org.example.seedancegenarate.entity.ComfyNode;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.mapper.ComfyNodeMapper;
import org.example.seedancegenarate.service.NodeHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

/**
 * 管理端 ComfyUI 机队运维：看状态、加节点、开关、调权重、归档。
 *
 * <h3>没有删除接口</h3>
 * {@code ComfyUiEngine.poll()} 靠 {@code video_task.node_id} 找回处理该任务的机器，
 * 查不到直接 {@code RemoteStatus.failed(...)} —— 那是<b>终态</b>不是重试。
 * 删掉一台正在跑 3 个 minimax 的机器 = 3 个任务当场判死 + 3 笔退款 +
 * 约 60 分钟 H100 机时白烧，而 GPU 上那 3 个 prompt 还会继续跑到完（成了孤儿）。
 * 历史任务的 node_id 也会一并悬空，事后归因从此查不了。
 * <p>
 * {@code archived} 拿到的是「列表里看不见」这个唯一真实需求，代价只是几百字节的行留着。
 */
@RestController
@RequestMapping("/api/admin/comfyui")
@RequiredArgsConstructor
public class AdminComfyUiController {

    private static final BigDecimal MAX_WEIGHT = new BigDecimal("99.99");

    private final NodeHealthService nodeHealthService;
    private final ComfyNodeMapper comfyNodeMapper;
    private final ComfyNodeRegistry comfyNodeRegistry;

    /** 机队状态。数据源是探测器的内存快照（最多旧 3 秒），打开页面不触发任何探测 */
    @GetMapping("/nodes")
    public Result<List<NodeHealth>> nodes(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        requireAdmin();
        List<NodeHealth> all = nodeHealthService.checkAll();
        return Result.success(includeArchived ? all : all.stream().filter(n -> !n.archived()).toList());
    }

    /**
     * 加一台节点。<b>{@code enabled} 强制 false，请求体里带了也不认。</b>
     * <p>
     * 让调用方能覆盖这个默认，等于允许一台还没装齐插件的机器立刻开始接真实用户的活。
     * 正确流程是：加进来 → 用「指定节点提交」把真实工作流跑通 → 再开。
     */
    @PostMapping("/nodes")
    public Result<Void> create(@RequestBody NodeUpsertRequest request) {
        requireAdmin();
        String nodeId = requiredText(request.getId(), "节点 id", 64);
        String baseUrl = validBaseUrl(request.getBaseUrl());
        validateWeight(request.getWeight());
        validateRemark(request.getRemark());
        if (comfyNodeMapper.selectById(nodeId) != null) {
            throw new IllegalArgumentException("节点 id 已存在: " + nodeId);
        }
        ComfyNode row = new ComfyNode();
        row.setId(nodeId);
        row.setBaseUrl(baseUrl);
        row.setEnabled(false); // 刻意不读 request.enabled
        row.setArchived(false);
        row.setWeight(request.getWeight() == null ? BigDecimal.ONE : request.getWeight());
        row.setRemark(request.getRemark());
        comfyNodeMapper.insert(row);
        comfyNodeRegistry.invalidate();
        return Result.success(null);
    }

    /** 改地址 / 开关 / 权重 / 备注 / 归档。只改传了的字段 */
    @PatchMapping("/nodes/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody NodeUpsertRequest request) {
        requireAdmin();
        ComfyNode existing = comfyNodeMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("节点不存在: " + id);
        }
        if (Boolean.TRUE.equals(request.getEnabled())
                && Boolean.TRUE.equals(existing.getArchived())
                && !Boolean.FALSE.equals(request.getArchived())) {
            throw new IllegalArgumentException("归档节点必须先取消归档才能启用");
        }
        var update = Wrappers.<ComfyNode>lambdaUpdate().eq(ComfyNode::getId, id);
        boolean changed = false;
        if (request.getBaseUrl() != null) {
            update.set(ComfyNode::getBaseUrl, validBaseUrl(request.getBaseUrl()));
            changed = true;
        }
        if (request.getEnabled() != null) {
            update.set(ComfyNode::getEnabled, request.getEnabled());
            changed = true;
        }
        if (request.getWeight() != null) {
            validateWeight(request.getWeight());
            update.set(ComfyNode::getWeight, request.getWeight());
            changed = true;
        }
        if (request.getRemark() != null) {
            validateRemark(request.getRemark());
            update.set(ComfyNode::getRemark, request.getRemark());
            changed = true;
        }
        if (request.getArchived() != null) {
            update.set(ComfyNode::getArchived, request.getArchived());
            changed = true;
            if (Boolean.TRUE.equals(request.getArchived())) {
                // 归档必然连带停用。分成两步的话，一个「已归档但还在派活」的节点
                // 会在列表里看不见却仍在接活 —— 那是最难发现的一种状态
                update.set(ComfyNode::getEnabled, false);
            }
        }
        if (!changed) {
            throw new IllegalArgumentException("至少提供一个要修改的字段");
        }
        comfyNodeMapper.update(null, update);
        comfyNodeRegistry.invalidate();
        return Result.success(null);
    }

    private String requiredText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private String validBaseUrl(String value) {
        String normalized = requiredText(value, "节点地址", 255);
        try {
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("节点地址必须是有效的 http/https URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("节点地址必须是有效的 http/https URL");
        }
        return normalized.replaceAll("/+$", "");
    }

    private void validateWeight(BigDecimal weight) {
        if (weight != null && (weight.signum() <= 0 || weight.compareTo(MAX_WEIGHT) > 0)) {
            throw new IllegalArgumentException("节点权重必须大于 0 且不超过 " + MAX_WEIGHT);
        }
    }

    private void validateRemark(String remark) {
        if (remark != null && remark.length() > 255) {
            throw new IllegalArgumentException("备注长度不能超过 255");
        }
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new RuntimeException("无权限访问");
        }
    }

    @Data
    public static class NodeUpsertRequest {
        private String id;
        private String baseUrl;
        /** 新增时<b>被忽略</b>（强制 false）；PATCH 时才生效 */
        private Boolean enabled;
        private Boolean archived;
        private BigDecimal weight;
        private String remark;
    }
}
