package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.CanvasNode;

import java.util.List;

/**
 * 画布执行：连线决定顺序。
 * <p>
 * 就绪 = 该节点的所有上游都已产出可用产物（源节点视为天然就绪）。运行只入队<b>当前就绪</b>的节点；
 * 某个节点终态后再看下游谁变就绪、补入队。上游失败 → 下游标 BLOCKED，不入队、不冻结钱。
 * <p>
 * 提交仍走 {@link VideoSubmitService}，冻结/结算/解冻仍是 D-003 那一套 —— 本服务只管顺序。
 */
public interface CanvasRunService {

    /** 运行整块画布：入队当前就绪的可执行节点，返回本次入队的节点 */
    List<CanvasNode> run(Long userId, Long canvasId);

    /** 运行单个节点（重试失败节点也走这里）；未就绪则抛出明确原因 */
    CanvasNode runNode(Long userId, Long canvasId, String nodeKey);

    /** 作业消费方调用：真正提交该节点（调用方需先原子占位防并发双提交） */
    void submitNodeForJob(Long nodeId) throws Exception;

    /** 终态事件回填：按 taskId 反查节点，写状态与产物，并推进下游 */
    void applyTaskFinished(String taskId, String status, String videoUrl, String errorMsg);

    /**
     * 对账一块画布，补上终态事件够不着的两种情况：
     * <ol>
     *   <li><b>任务已终态、节点还挂在 PROCESSING</b>：提交是「先建任务，再把 taskId 写回节点」两步，
     *       中间任务就可能已经跑完（画布上失败往往只要几秒）。那一刻按 task_id 反查不到节点，
     *       终态事件白发一次，节点从此永远停在「生成中」。</li>
     *   <li>PENDING 节点没有活跃提交作业（实例重启丢了在途作业）→ 补插作业让 Worker 接管。</li>
     * </ol>
     */
    void reconcileRunning(Long canvasId);
}
