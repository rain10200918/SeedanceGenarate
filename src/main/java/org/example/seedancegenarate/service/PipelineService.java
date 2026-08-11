package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;

import java.util.List;
import java.util.Map;

/**
 * 分镜流水线：可复用的批量制作流程。运行/重试复用 {@link VideoSubmitService} 提交链路，
 * 节点按 taskId 监听终态事件回填（后端为单一事实源，刷新不丢状态）。
 */
public interface PipelineService {

    List<Pipeline> listPipelines(Long userId);

    /** 创建（自动带 1 个 INPUT 素材池节点） */
    Pipeline createPipeline(Long userId, String title);

    /** 详情：{pipeline, nodes} */
    Map<String, Object> getDetail(Long userId, Long pipelineId);

    void updatePipeline(Long userId, Long pipelineId, String title, String provider, String model);

    void deletePipeline(Long userId, Long pipelineId);

    /** 复制为模板：节点原样克隆，状态回 DRAFT */
    Pipeline copyPipeline(Long userId, Long pipelineId);

    /** 全量保存节点编排（删旧插新，状态回 DRAFT 可重新运行） */
    void saveNodes(Long userId, Long pipelineId, List<NodeDraft> nodes);

    /** 运行：状态门 + 校验 + 逐 SCENE 节点复用 submit；单个节点失败标 FAILED，不中断其余 */
    Pipeline run(Long userId, Long pipelineId) throws Exception;

    /** 单节点重试（仅 FAILED）；重新提交并替换 taskId */
    PipelineNode retryNode(Long userId, Long pipelineId, Long nodeId) throws Exception;

    /** 终态事件回填：按 taskId 更新节点状态/结果并汇总流水线状态（由 PipelineEventListener 调用） */
    void applyTaskFinished(String taskId, String status, String videoUrl, String errorMsg);

    /** 提交分镜节点（作业消费方调用；调用方必须先原子占位防并发双提交） */
    void submitNodeForJob(Long nodeId) throws Exception;

    /**
     * 对账一条 RUNNING 流水线：节点全终态则汇总状态；存在 PENDING 节点但没有活跃
     * 提交作业时补插作业（替代旧的「启动时全量置 PARTIAL_FAILED」恢复方式）。
     */
    void reconcileRunning(Long pipelineId);

    /** 节点编排草稿（全量保存入参；assetIds 引用 user_asset.id；model 空=跟随流水线模型） */
    record NodeDraft(String kind, String name, List<Long> assetIds, String prompt, Integer duration, String ratio,
                     String model) {
    }
}
