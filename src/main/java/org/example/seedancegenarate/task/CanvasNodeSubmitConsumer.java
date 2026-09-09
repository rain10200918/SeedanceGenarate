package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.Canvas;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.CanvasMapper;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.Impl.CanvasRunServiceImpl;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;


/**
 * 画布节点提交作业消费：领取 CANVAS_NODE_SUBMIT → 原子占位 → 提交 → 完成/重试。
 * <p>
 * 与流水线消费者同一套路：行级租约保证同一作业只被一个 Worker 领取，节点 CAS 占位保证
 * 同一节点只被提交一次；实例崩溃由租约过期 + 下一轮扫描恢复。不需要全局分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasNodeSubmitConsumer implements AsyncJobHandler {
    private static final long LEASE_SECONDS = 60;

    private final AsyncJobService asyncJobService;
    private final CanvasRunService canvasRunService;
    private final CanvasNodeMapper canvasNodeMapper;
    private final CanvasMapper canvasMapper;
    private final VideoSubmitService videoSubmitService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String jobType() {
        return CanvasRunServiceImpl.JOB_TYPE;
    }

    @Override
    public long leaseSeconds() {
        return LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        Payload payload = parse(job.getPayload());
        if (payload == null || payload.nodeId() == null) {
            asyncJobService.complete(job);
            return;
        }
        Long nodeId = payload.nodeId();
        String resolvedRequestId = payload.expectedRequestId();
        if (!StringUtils.hasText(resolvedRequestId)) {
            // 升级前 payload 没带代际；只读取一次当前 requestId 并从此固化，后续仍走同一 CAS。
            CanvasNode current = canvasNodeMapper.selectById(nodeId);
            resolvedRequestId = current == null ? null : current.getSubmitRequestId();
            if (!StringUtils.hasText(resolvedRequestId)) {
                asyncJobService.complete(job);
                return;
            }
        }
        final String expectedRequestId = resolvedRequestId;
        // 原子占位：PENDING/FAILED/BLOCKED → PROCESSING；0 行说明另一 Worker 已提交或节点已完成
        if (canvasNodeMapper.occupyForSubmit(nodeId, expectedRequestId) != 1) {
            finishOccupiedJob(job, nodeId, expectedRequestId);
            return;
        }
        try {
            canvasRunService.submitNodeForJob(nodeId, expectedRequestId);
            asyncJobService.complete(job);
        } catch (Exception e) {
            // 外部 submit 已结束；只把 fenced job 转移与节点 FAILED 这段纯 DB 收尾放在同一事务。
            transactionTemplate.executeWithoutResult(ignored -> {
                CanvasNode current = canvasNodeMapper.selectById(nodeId);
                if (current == null || !expectedRequestId.equals(current.getSubmitRequestId())) {
                    asyncJobService.complete(job);
                    return;
                }
                if (StringUtils.hasText(current.getTaskId())) {
                    // 提交实际已成功（网络超时等），只是回写失败：不重复提交，作业完成
                    log.warn("画布节点提交结果不确定但已有 taskId，按成功处理: nodeId={}, reason={}",
                            nodeId, e.getMessage());
                    asyncJobService.complete(job);
                    return;
                }
                if (recoverTaskLink(current)) {
                    log.warn("画布节点提交结果不确定，已按 requestId 补回 taskId: nodeId={}", nodeId);
                    asyncJobService.complete(job);
                    return;
                }
                if (!asyncJobService.failAndRetry(job, e.getMessage())) {
                    return;
                }
                if (current != null) {
                    canvasNodeMapper.releaseForRetryIfUnlinked(
                            nodeId, expectedRequestId, truncate(e.getMessage()));
                }
            });
        }
    }

    /**
     * occupy=0 不等于作业已经完成：旧 Worker 可能在创建任务后、回写节点前宕机。
     * 仅当节点不再等待补链，或补链成功时完成作业；否则在 fenced job 退避的
     * 同一短事务内将当前运行代际退回 FAILED，供下一租约重新占位。
     */
    private void finishOccupiedJob(AsyncJob job, Long nodeId, String expectedRequestId) {
        CanvasNode current = canvasNodeMapper.selectById(nodeId);
        if (current == null || !expectedRequestId.equals(current.getSubmitRequestId())
                || !"PROCESSING".equals(current.getStatus())
                || StringUtils.hasText(current.getTaskId())) {
            asyncJobService.complete(job);
            return;
        }
        if (recoverTaskLink(current)) {
            log.warn("画布节点按 requestId 恢复任务关联: nodeId={}, requestId={}",
                    nodeId, current.getSubmitRequestId());
            asyncJobService.complete(job);
            return;
        }
        String reason = "画布节点生成任务关联尚不可见";
        transactionTemplate.executeWithoutResult(ignored -> {
            if (asyncJobService.failAndRetry(job, reason)) {
                canvasNodeMapper.releaseForRetryIfUnlinked(
                        nodeId, expectedRequestId, reason);
            }
        });
    }

    /** 只补当前运行代际的空 taskId；任务尚未完成持久化时返回 false，由作业重试。 */
    private boolean recoverTaskLink(CanvasNode node) {
        if (node == null || node.getId() == null || !"PROCESSING".equals(node.getStatus())
                || StringUtils.hasText(node.getTaskId())
                || !StringUtils.hasText(node.getSubmitRequestId())) {
            return false;
        }
        Canvas canvas = canvasMapper.selectById(node.getCanvasId());
        if (canvas == null || canvas.getUserId() == null) {
            return false;
        }
        VideoTask task = videoSubmitService.findByRequestId(canvas.getUserId(), node.getSubmitRequestId());
        if (!isDurablyQueued(task)) {
            return false;
        }
        String taskId = task.businessTaskId();
        if (canvasNodeMapper.linkTaskIfMissing(node.getId(), node.getSubmitRequestId(), taskId) == 1) {
            return true;
        }
        CanvasNode latest = canvasNodeMapper.selectById(node.getId());
        return latest != null && StringUtils.hasText(latest.getTaskId());
    }

    /** 避免认领到 submit() 尚未完成 attempt/job 事务、随后可能被补偿删除的半成品任务。 */
    private boolean isDurablyQueued(VideoTask task) {
        if (task == null || !StringUtils.hasText(task.businessTaskId())) {
            return false;
        }
        if (!"PROCESSING".equals(task.getStatus())) {
            return true;
        }
        return task.getCurrentAttemptId() != null
                || StringUtils.hasText(task.getPhase())
                || StringUtils.hasText(task.getProviderTaskId());
    }

    private Payload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode nodeId = json.get("canvasNodeId");
            JsonNode requestId = json.get("expectedRequestId");
            return new Payload(nodeId == null || nodeId.isNull() ? null : nodeId.asLong(),
                    requestId == null || requestId.isNull() ? null : requestId.asText());
        } catch (Exception e) {
            log.warn("解析画布节点作业参数失败: {}", payload);
            return null;
        }
    }

    private record Payload(Long nodeId, String expectedRequestId) {
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
