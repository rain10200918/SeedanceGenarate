package org.example.seedancegenarate.controller;

import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.PipelineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 分镜流水线：可复用的批量制作流程（编排 → 运行 → 单节点重试 → 复制模板） */
@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @GetMapping
    public Result<List<Pipeline>> list() {
        return Result.success(pipelineService.listPipelines(UserContext.requireUserId()));
    }

    @PostMapping
    public Result<Pipeline> create(@RequestBody CreateRequest req) {
        return Result.success(pipelineService.createPipeline(UserContext.requireUserId(), req.title()));
    }

    /** 详情（含节点，按 seq 排序） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(pipelineService.getDetail(UserContext.requireUserId(), id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        pipelineService.updatePipeline(UserContext.requireUserId(), id, req.title(), req.provider(), req.model());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        pipelineService.deletePipeline(UserContext.requireUserId(), id);
        return Result.success(null);
    }

    /** 复制为模板：节点原样克隆，状态回 DRAFT */
    @PostMapping("/{id}/copy")
    public Result<Pipeline> copy(@PathVariable Long id) {
        return Result.success(pipelineService.copyPipeline(UserContext.requireUserId(), id));
    }

    /** 全量保存节点编排（删旧插新；保存后状态回 DRAFT 可重新运行） */
    @PutMapping("/{id}/nodes")
    public Result<Void> saveNodes(@PathVariable Long id, @RequestBody SaveNodesRequest req) {
        pipelineService.saveNodes(UserContext.requireUserId(), id, req.nodes());
        return Result.success(null);
    }

    /** 运行：逐分镜节点复用提交链路；单个节点失败不中断其余 */
    @PostMapping("/{id}/run")
    public Result<Pipeline> run(@PathVariable Long id) throws Exception {
        return Result.success(pipelineService.run(UserContext.requireUserId(), id));
    }

    /** 单节点重试（仅失败节点） */
    @PostMapping("/{id}/nodes/{nodeId}/retry")
    public Result<PipelineNode> retry(@PathVariable Long id, @PathVariable Long nodeId) throws Exception {
        return Result.success(pipelineService.retryNode(UserContext.requireUserId(), id, nodeId));
    }

    public record CreateRequest(String title) {
    }

    public record UpdateRequest(String title, String provider, String model) {
    }

    public record SaveNodesRequest(List<PipelineService.NodeDraft> nodes) {
    }
}
