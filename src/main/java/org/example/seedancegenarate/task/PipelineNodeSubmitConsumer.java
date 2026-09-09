package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.example.seedancegenarate.mapper.PipelineNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.PipelineService;
import org.example.seedancegenarate.service.VideoSubmitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;


/**
 * 流水线节点提交作业消费：领取 PIPELINE_NODE_SUBMIT → 原子占位 → 提交 → 完成/重试。
 * <p>
 * 不需要全局分布式锁：行级租约（claim）保证同一作业只被一个 Worker 领取，
 * 节点 CAS 占位保证同一节点只被提交一次；实例崩溃由租约过期 + 对账补跑恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineNodeSubmitConsumer implements AsyncJobHandler {
    private static final String JOB_TYPE = "PIPELINE_NODE_SUBMIT";
    private static final long LEASE_SECONDS = 60;

    private final AsyncJobService asyncJobService;
    private final PipelineService pipelineService;
    private final PipelineNodeMapper pipelineNodeMapper;
    private final PipelineMapper pipelineMapper;
    private final VideoSubmitService videoSubmitService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${pipeline.job-driven:true}")
    private boolean jobDriven;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public long leaseSeconds() {
        return LEASE_SECONDS;
    }

    @Override
    public boolean enabled() {
        return jobDriven;
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
            PipelineNode current = pipelineNodeMapper.selectById(nodeId);
            resolvedRequestId = current == null ? null : current.getSubmitRequestId();
            if (!StringUtils.hasText(resolvedRequestId)) {
                asyncJobService.complete(job);
                return;
            }
        }
        final String expectedRequestId = resolvedRequestId;
        // 原子占位：PENDING/FAILED → PROCESSING；0 行说明另一 Worker 已提交（或节点已完成）
        if (pipelineNodeMapper.occupyForSubmit(nodeId, expectedRequestId) != 1) {
            finishOccupiedJob(job, nodeId, expectedRequestId);
            return;
        }
        try {
            pipelineService.submitNodeForJob(nodeId, expectedRequestId);
            asyncJobService.complete(job);
        } catch (Exception e) {
            // 外部 submit 已结束；只把 fenced job 转移与节点 FAILED 这段纯 DB 收尾放在同一事务。
            transactionTemplate.executeWithoutResult(ignored -> {
                PipelineNode current = pipelineNodeMapper.selectById(nodeId);
                if (current == null || !expectedRequestId.equals(current.getSubmitRequestId())) {
                    asyncJobService.complete(job);
                    return;
                }
                if (StringUtils.hasText(current.getTaskId())) {
                    // 提交实际已成功（网络超时等），只是回写失败：不重复提交，作业完成
                    log.warn("节点提交结果不确定但已有 taskId，按成功处理: nodeId={}, reason={}",
                            nodeId, e.getMessage());
                    asyncJobService.complete(job);
                    return;
                }
                if (recoverTaskLink(current)) {
                    log.warn("节点提交结果不确定，已按 requestId 补回 taskId: nodeId={}", nodeId);
                    asyncJobService.complete(job);
                    return;
                }
                if (!asyncJobService.failAndRetry(job, e.getMessage())) {
                    return;
                }
                if (current != null) {
                    pipelineNodeMapper.releaseForRetryIfUnlinked(
                            nodeId, expectedRequestId, truncate(e.getMessage()));
                }
            });
        }
    }

    /** occupy=0 可能是旧 Worker 死在“任务落库、节点回写”之间；补不回时要栅栏回退，不能吞作业或留下 PROCESSING 死结。 */
    private void finishOccupiedJob(AsyncJob job, Long nodeId, String expectedRequestId) {
        PipelineNode current = pipelineNodeMapper.selectById(nodeId);
        if (current == null || !expectedRequestId.equals(current.getSubmitRequestId())
                || !"PROCESSING".equals(current.getStatus())
                || StringUtils.hasText(current.getTaskId())) {
            asyncJobService.complete(job);
            return;
        }
        if (recoverTaskLink(current)) {
            log.warn("流水线节点按 requestId 恢复任务关联: nodeId={}, requestId={}",
                    nodeId, current.getSubmitRequestId());
            asyncJobService.complete(job);
            return;
        }
        String reason = "流水线节点生成任务关联尚不可见";
        transactionTemplate.executeWithoutResult(ignored -> {
            if (asyncJobService.failAndRetry(job, reason)) {
                pipelineNodeMapper.releaseForRetryIfUnlinked(
                        nodeId, expectedRequestId, reason);
            }
        });
    }

    /** 只补当前运行代际的空 taskId；任务尚未完成持久化时返回 false，由作业重试。 */
    private boolean recoverTaskLink(PipelineNode node) {
        if (node == null || node.getId() == null || !"PROCESSING".equals(node.getStatus())
                || StringUtils.hasText(node.getTaskId())
                || !StringUtils.hasText(node.getSubmitRequestId())) {
            return false;
        }
        Pipeline pipeline = pipelineMapper.selectById(node.getPipelineId());
        if (pipeline == null || pipeline.getUserId() == null) {
            return false;
        }
        VideoTask task = videoSubmitService.findByRequestId(pipeline.getUserId(), node.getSubmitRequestId());
        if (!isDurablyQueued(task)) {
            return false;
        }
        String taskId = task.businessTaskId();
        if (pipelineNodeMapper.linkTaskIfMissing(node.getId(), node.getSubmitRequestId(), taskId) == 1) {
            return true;
        }
        PipelineNode latest = pipelineNodeMapper.selectById(node.getId());
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
            JsonNode nodeId = json.get("pipelineNodeId");
            JsonNode requestId = json.get("expectedRequestId");
            return new Payload(nodeId == null || nodeId.isNull() ? null : nodeId.asLong(),
                    requestId == null || requestId.isNull() ? null : requestId.asText());
        } catch (Exception e) {
            log.warn("解析流水线节点作业参数失败: {}", payload);
            return null;
        }
    }

    private record Payload(Long nodeId, String expectedRequestId) {
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
