package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.entity.UserAsset;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.example.seedancegenarate.mapper.PipelineNodeMapper;
import org.example.seedancegenarate.mapper.UserAssetMapper;
import org.example.seedancegenarate.service.PipelineService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流水线实现要点：
 * - run 不包长事务：状态门一段（只读）+ 逐节点独立提交回填（submit 是重操作，不能锁整条）
 * - 节点按 taskId 关联任务，终态事件（TaskStatusChangedEvent）驱动回填——后端单一事实源
 * - 复制不复制 taskId/errorMsg（运行结果不继承，重新跑）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineServiceImpl implements PipelineService {

    private final PipelineMapper pipelineMapper;
    private final PipelineNodeMapper pipelineNodeMapper;
    private final UserAssetMapper userAssetMapper;
    private final VideoSubmitService videoSubmitService;
    private final ObjectMapper objectMapper;

    @Override
    public List<Pipeline> listPipelines(Long userId) {
        return pipelineMapper.selectList(new LambdaQueryWrapper<Pipeline>()
                .eq(Pipeline::getUserId, userId)
                .orderByDesc(Pipeline::getId));
    }

    @Override
    @Transactional
    public Pipeline createPipeline(Long userId, String title) {
        String t = (title == null || title.isBlank()) ? "未命名流水线" : title.trim();
        Pipeline pipeline = new Pipeline();
        pipeline.setUserId(userId);
        pipeline.setTitle(t);
        pipeline.setStatus("DRAFT");
        pipelineMapper.insert(pipeline);
        PipelineNode input = new PipelineNode();
        input.setPipelineId(pipeline.getId());
        input.setSeq(0);
        input.setKind("INPUT");
        input.setName("素材池");
        input.setStatus("PENDING");
        pipelineNodeMapper.insert(input);
        return pipeline;
    }

    @Override
    public Map<String, Object> getDetail(Long userId, Long pipelineId) {
        Pipeline pipeline = requireOwned(userId, pipelineId);
        List<PipelineNode> nodes = pipelineNodeMapper.selectList(new LambdaQueryWrapper<PipelineNode>()
                .eq(PipelineNode::getPipelineId, pipelineId)
                .orderByAsc(PipelineNode::getSeq));
        Map<String, Object> result = new HashMap<>();
        result.put("pipeline", pipeline);
        result.put("nodes", nodes);
        return result;
    }

    @Override
    public void updatePipeline(Long userId, Long pipelineId, String title, String provider, String model) {
        Pipeline pipeline = requireOwned(userId, pipelineId);
        if (StringUtils.hasText(title)) {
            pipeline.setTitle(title.trim());
        }
        pipeline.setProvider(provider);
        pipeline.setModel(model);
        pipelineMapper.updateById(pipeline);
    }

    @Override
    @Transactional
    public void deletePipeline(Long userId, Long pipelineId) {
        requireOwned(userId, pipelineId);
        pipelineNodeMapper.delete(new LambdaQueryWrapper<PipelineNode>().eq(PipelineNode::getPipelineId, pipelineId));
        pipelineMapper.deleteById(pipelineId);
    }

    @Override
    @Transactional
    public Pipeline copyPipeline(Long userId, Long pipelineId) {
        Pipeline src = requireOwned(userId, pipelineId);
        Pipeline copy = new Pipeline();
        copy.setUserId(userId);
        copy.setTitle(src.getTitle() + "（副本）");
        copy.setProvider(src.getProvider());
        copy.setModel(src.getModel());
        copy.setStatus("DRAFT");
        pipelineMapper.insert(copy);
        List<PipelineNode> nodes = pipelineNodeMapper.selectList(new LambdaQueryWrapper<PipelineNode>()
                .eq(PipelineNode::getPipelineId, pipelineId)
                .orderByAsc(PipelineNode::getSeq));
        for (PipelineNode n : nodes) {
            PipelineNode c = new PipelineNode();
            c.setPipelineId(copy.getId());
            c.setSeq(n.getSeq());
            c.setKind(n.getKind());
            c.setName(n.getName());
            c.setAssetIds(n.getAssetIds());
            c.setPrompt(n.getPrompt());
            c.setDuration(n.getDuration());
            c.setRatio(n.getRatio());
            c.setStatus("PENDING");
            pipelineNodeMapper.insert(c);
        }
        return copy;
    }

    @Override
    @Transactional
    public void saveNodes(Long userId, Long pipelineId, List<NodeDraft> drafts) {
        requireOwned(userId, pipelineId);
        if (drafts == null || drafts.isEmpty()) {
            throw new RuntimeException("节点列表不能为空");
        }
        pipelineNodeMapper.delete(new LambdaQueryWrapper<PipelineNode>().eq(PipelineNode::getPipelineId, pipelineId));
        int seq = 0;
        for (NodeDraft d : drafts) {
            PipelineNode n = new PipelineNode();
            n.setPipelineId(pipelineId);
            n.setSeq(seq++);
            n.setKind("INPUT".equals(d.kind()) ? "INPUT" : "SCENE");
            n.setName(d.name());
            n.setAssetIds(toJson(d.assetIds()));
            n.setPrompt(d.prompt());
            n.setDuration(d.duration());
            n.setRatio(d.ratio());
            n.setModel(d.model());
            n.setStatus("PENDING");
            pipelineNodeMapper.insert(n);
        }
        // 保存编排 = 重新开始：状态回 DRAFT，允许再次运行
        Pipeline pipeline = requireOwned(userId, pipelineId);
        pipeline.setStatus("DRAFT");
        pipelineMapper.updateById(pipeline);
    }

    @Override
    public Pipeline run(Long userId, Long pipelineId) throws Exception {
        Pipeline pipeline = requireOwned(userId, pipelineId);
        if (!"DRAFT".equals(pipeline.getStatus()) && !"PARTIAL_FAILED".equals(pipeline.getStatus())) {
            throw new RuntimeException("当前状态不可运行（运行中或已完成）");
        }
        List<PipelineNode> scenes = scenesOf(pipelineId);
        if (scenes.isEmpty()) {
            throw new RuntimeException("请先添加分镜节点");
        }
        pipeline.setStatus("RUNNING");
        pipelineMapper.updateById(pipeline);
        // 逐节点独立提交：单个节点失败标 FAILED，不中断其余节点
        for (PipelineNode node : scenes) {
            try {
                submitNode(userId, pipeline, node);
            } catch (Exception e) {
                log.warn("流水线节点提交失败 pipelineId={} nodeId={}: {}", pipelineId, node.getId(), e.getMessage());
                node.setStatus("FAILED");
                node.setErrorMsg(truncate(e.getMessage()));
                pipelineNodeMapper.updateById(node);
            }
        }
        refreshPipelineStatus(pipeline);
        return pipeline;
    }

    @Override
    public PipelineNode retryNode(Long userId, Long pipelineId, Long nodeId) throws Exception {
        Pipeline pipeline = requireOwned(userId, pipelineId);
        PipelineNode node = pipelineNodeMapper.selectById(nodeId);
        if (node == null || !Objects.equals(node.getPipelineId(), pipelineId)) {
            throw new RuntimeException("节点不存在");
        }
        // 单节点执行：待执行 / 失败 / 已完成 均可单独重跑（生成中禁止，防重复提交）
        if ("PROCESSING".equals(node.getStatus())) {
            throw new RuntimeException("该分镜正在生成中，请稍候");
        }
        try {
            submitNode(userId, pipeline, node);
        } catch (Exception e) {
            node.setStatus("FAILED");
            node.setErrorMsg(truncate(e.getMessage()));
            pipelineNodeMapper.updateById(node);
            throw new RuntimeException("执行失败：" + e.getMessage());
        }
        refreshPipelineStatus(pipeline);
        return node;
    }

    @Override
    public void applyTaskFinished(String taskId, String status, String videoUrl, String errorMsg) {
        if (!StringUtils.hasText(taskId)) return;
        PipelineNode node = pipelineNodeMapper.selectOne(
                new LambdaQueryWrapper<PipelineNode>().eq(PipelineNode::getTaskId, taskId));
        if (node == null) return;
        if ("SUCCESS".equals(status)) {
            node.setStatus("SUCCESS");
            node.setVideoUrl(videoUrl);
            node.setErrorMsg(null);
        } else if ("FAILED".equals(status)) {
            node.setStatus("FAILED");
            node.setErrorMsg(truncate(errorMsg));
        } else {
            return;
        }
        pipelineNodeMapper.updateById(node);
        Pipeline pipeline = pipelineMapper.selectById(node.getPipelineId());
        if (pipeline != null) {
            refreshPipelineStatus(pipeline);
        }
    }

    /** 单节点提交：校验 + 素材 ID → URL + 复用 submit 链路；成功后节点回 PROCESSING */
    private void submitNode(Long userId, Pipeline pipeline, PipelineNode node) throws Exception {
        if (!StringUtils.hasText(node.getPrompt())) {
            throw new RuntimeException("提示词不能为空");
        }
        List<Long> assetIds = parseAssetIds(node.getAssetIds());
        if (assetIds.isEmpty()) {
            throw new RuntimeException("未选择参考图");
        }
        List<String> urls = resolveAssetUrls(userId, assetIds);
        // 分镜独立模型优先，空则跟随流水线模型（统一默认由 submit 链路解析）
        String effectiveModel = StringUtils.hasText(node.getModel()) ? node.getModel() : pipeline.getModel();
        VideoTask task = videoSubmitService.submit(new VideoSubmitService.SubmitRequest(
                userId, pipeline.getProvider(), effectiveModel,
                node.getPrompt(), urls,
                node.getDuration(), node.getRatio(), null, null));
        node.setTaskId(task.getTaskId());
        node.setStatus("PROCESSING");
        node.setErrorMsg(null);
        pipelineNodeMapper.updateById(node);
    }

    /** 素材 ID 引用 → URL（实体引用：素材换存储位置流水线不受影响） */
    private List<String> resolveAssetUrls(Long userId, List<Long> assetIds) {
        List<UserAsset> assets = userAssetMapper.selectBatchIds(assetIds);
        if (assets.size() != assetIds.size()) {
            throw new RuntimeException("部分参考图不存在或已被删除");
        }
        List<String> urls = new ArrayList<>();
        for (UserAsset a : assets) {
            if (!Objects.equals(a.getUserId(), userId) || !"ACTIVE".equals(a.getStatus())) {
                throw new RuntimeException("参考图不存在或已被删除");
            }
            urls.add(a.getUrl());
        }
        return urls;
    }

    private List<PipelineNode> scenesOf(Long pipelineId) {
        return pipelineNodeMapper.selectList(new LambdaQueryWrapper<PipelineNode>()
                .eq(PipelineNode::getPipelineId, pipelineId)
                .eq(PipelineNode::getKind, "SCENE")
                .orderByAsc(PipelineNode::getSeq));
    }

    /** 汇总流水线状态：全 SUCCESS → DONE；有运行中 → RUNNING；有失败 → PARTIAL_FAILED；其余 DRAFT */
    private void refreshPipelineStatus(Pipeline pipeline) {
        List<PipelineNode> scenes = scenesOf(pipeline.getId());
        if (scenes.isEmpty()) return;
        boolean allSuccess = scenes.stream().allMatch(n -> "SUCCESS".equals(n.getStatus()));
        boolean anyFailed = scenes.stream().anyMatch(n -> "FAILED".equals(n.getStatus()));
        boolean anyProcessing = scenes.stream().anyMatch(n -> "PROCESSING".equals(n.getStatus()));
        String status = allSuccess ? "DONE"
                : anyProcessing ? "RUNNING"
                : anyFailed ? "PARTIAL_FAILED"
                : "DRAFT";
        if (!status.equals(pipeline.getStatus())) {
            pipeline.setStatus(status);
            pipelineMapper.updateById(pipeline);
        }
    }

    private Pipeline requireOwned(Long userId, Long pipelineId) {
        Pipeline pipeline = pipelineMapper.selectById(pipelineId);
        if (pipeline == null || !Objects.equals(pipeline.getUserId(), userId)) {
            throw new RuntimeException("流水线不存在");
        }
        return pipeline;
    }

    private List<Long> parseAssetIds(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
            return ids == null ? new ArrayList<>() : ids;
        } catch (Exception e) {
            log.warn("解析节点素材引用失败: {}", json);
            return new ArrayList<>();
        }
    }

    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
