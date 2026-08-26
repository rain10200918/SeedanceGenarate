package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.Impl.CanvasRunServiceImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 画布节点提交作业消费：领取 CANVAS_NODE_SUBMIT → 原子占位 → 提交 → 完成/重试。
 * <p>
 * 与流水线消费者同一套路：行级租约保证同一作业只被一个 Worker 领取，节点 CAS 占位保证
 * 同一节点只被提交一次；实例崩溃由租约过期 + 下一轮扫描恢复。不需要全局分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasNodeSubmitConsumer {

    private final AsyncJobService asyncJobService;
    private final CanvasRunService canvasRunService;
    private final CanvasNodeMapper canvasNodeMapper;
    private final AsyncJobProperties properties;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${async-job.reconcile-interval-ms:30000}",
            initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void consumePendingSubmits() {
        consumeNow();
    }

    /** 即时消费一轮（多实例竞争由行级租约保证安全） */
    public void consumeNow() {
        List<AsyncJob> jobs = asyncJobService.claimBatch(
                CanvasRunServiceImpl.JOB_TYPE, properties.getClaimBatchSize(), properties.getLeaseSeconds());
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
        // 原子占位：PENDING/FAILED/BLOCKED → PROCESSING；0 行说明另一 Worker 已提交或节点已完成
        if (canvasNodeMapper.occupyForSubmit(nodeId) != 1) {
            asyncJobService.complete(job.getId(), job.getLeaseToken());
            return;
        }
        try {
            canvasRunService.submitNodeForJob(nodeId);
            asyncJobService.complete(job.getId(), job.getLeaseToken());
        } catch (Exception e) {
            CanvasNode current = canvasNodeMapper.selectById(nodeId);
            if (current != null && StringUtils.hasText(current.getTaskId())) {
                // 提交实际已成功（网络超时等），只是回写失败：不重复提交，作业完成
                log.warn("画布节点提交结果不确定但已有 taskId，按成功处理: nodeId={}, reason={}",
                        nodeId, e.getMessage());
                asyncJobService.complete(job.getId(), job.getLeaseToken());
                return;
            }
            CanvasNode failed = new CanvasNode();
            failed.setId(nodeId);
            failed.setStatus("FAILED");
            failed.setErrorMsg(truncate(e.getMessage()));
            canvasNodeMapper.updateById(failed);
            asyncJobService.failAndRetry(job.getId(), job.getLeaseToken(), e.getMessage());
        }
    }

    private Long parseNodeId(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode nodeId = json.get("canvasNodeId");
            return nodeId == null || nodeId.isNull() ? null : nodeId.asLong();
        } catch (Exception e) {
            log.warn("解析画布节点作业参数失败: {}", payload);
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
