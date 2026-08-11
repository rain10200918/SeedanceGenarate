package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.PipelineNode;
import org.example.seedancegenarate.mapper.PipelineNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.PipelineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 流水线节点提交作业消费：领取 PIPELINE_NODE_SUBMIT → 原子占位 → 提交 → 完成/重试。
 * <p>
 * 不需要全局分布式锁：行级租约（claim）保证同一作业只被一个 Worker 领取，
 * 节点 CAS 占位保证同一节点只被提交一次；实例崩溃由租约过期 + 对账补跑恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineNodeSubmitConsumer {
    private static final String JOB_TYPE = "PIPELINE_NODE_SUBMIT";

    private final AsyncJobService asyncJobService;
    private final PipelineService pipelineService;
    private final PipelineNodeMapper pipelineNodeMapper;
    private final AsyncJobProperties properties;
    private final ObjectMapper objectMapper;

    @Value("${pipeline.job-driven:true}")
    private boolean jobDriven;

    /** 低频兜底扫描（事件通知丢失时接管）；正常由 Redis 通知即时唤醒。 */
    @Scheduled(fixedDelayString = "${async-job.reconcile-interval-ms:30000}",
            initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void consumePendingSubmits() {
        consumeNow();
    }

    /** 即时消费一轮（Redis 作业通知到达时调用；行级租约保证多实例竞争安全）。 */
    public void consumeNow() {
        if (!jobDriven) {
            return;
        }
        List<AsyncJob> jobs = asyncJobService.claimBatch(JOB_TYPE, properties.getClaimBatchSize(),
                properties.getLeaseSeconds());
        for (AsyncJob job : jobs) {
            consume(job);
        }
    }

    private void consume(AsyncJob job) {
        Long nodeId = parseNodeId(job.getPayload());
        if (nodeId == null) {
            asyncJobService.complete(job.getId(), job.getLeaseToken());
            return;
        }
        // 原子占位：PENDING/FAILED → PROCESSING；0 行说明另一 Worker 已提交（或节点已完成）
        if (pipelineNodeMapper.occupyForSubmit(nodeId) != 1) {
            asyncJobService.complete(job.getId(), job.getLeaseToken());
            return;
        }
        try {
            pipelineService.submitNodeForJob(nodeId);
            asyncJobService.complete(job.getId(), job.getLeaseToken());
        } catch (Exception e) {
            PipelineNode current = pipelineNodeMapper.selectById(nodeId);
            if (current != null && StringUtils.hasText(current.getTaskId())) {
                // 提交实际已成功（网络超时等），只是回写失败：不重复提交，作业完成
                log.warn("节点提交结果不确定但已有 taskId，按成功处理: nodeId={}, reason={}",
                        nodeId, e.getMessage());
                asyncJobService.complete(job.getId(), job.getLeaseToken());
                return;
            }
            // 确实未提交成功：节点回 FAILED，作业退避重试（超上限进 DEAD）
            PipelineNode failed = new PipelineNode();
            failed.setId(nodeId);
            failed.setStatus("FAILED");
            failed.setErrorMsg(truncate(e.getMessage()));
            pipelineNodeMapper.updateById(failed);
            asyncJobService.failAndRetry(job.getId(), job.getLeaseToken(), e.getMessage());
        }
    }

    private Long parseNodeId(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode nodeId = json.get("pipelineNodeId");
            return nodeId == null || nodeId.isNull() ? null : nodeId.asLong();
        } catch (Exception e) {
            log.warn("解析流水线节点作业参数失败: {}", payload);
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
