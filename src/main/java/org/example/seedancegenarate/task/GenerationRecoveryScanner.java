package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.comfyui.ComfyUiFleet;
import org.example.seedancegenarate.engine.comfyui.NodeState;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 给存量 UNKNOWN 补恢复作业，并在故障节点重新在线后唤醒已耗尽的恢复作业。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationRecoveryScanner {
    private static final int BATCH_SIZE = 100;

    private final GenerationAttemptMapper attemptMapper;
    private final AsyncJobService asyncJobService;
    private final GenerationAttemptService attemptService;
    private final ComfyUiFleet comfyUiFleet;

    @Scheduled(fixedDelayString = "${video.comfyui.recovery-scan-ms:10000}",
            initialDelayString = "${async-job.initial-delay-ms:10000}")
    public void scan() {
        for (GenerationAttempt attempt : attemptMapper.findSubmitUnknown(BATCH_SIZE)) {
            try {
                String key = GenerationAttemptService.jobKey(attempt.getId());
                AsyncJob existing = asyncJobService.find(GenerationAttemptService.RECOVERY_JOB_TYPE, key);
                if (existing == null || shouldReopenDead(existing, attempt)) {
                    attemptService.ensureRecoveryScheduled(attempt.getId());
                }
            } catch (Exception e) {
                log.warn("补建生成恢复作业失败: attemptId={}, reason={}", attempt.getId(), e.getMessage());
            }
        }
    }

    private boolean shouldReopenDead(AsyncJob job, GenerationAttempt attempt) {
        if (!AsyncJob.STATUS_DEAD.equals(job.getStatus())) {
            return false;
        }
        String nodeId = StringUtils.hasText(attempt.getNodeId())
                ? attempt.getNodeId().trim()
                : (StringUtils.hasText(attempt.getRequestedNodeId())
                ? attempt.getRequestedNodeId().trim() : null);
        NodeState node = comfyUiFleet.node(nodeId);
        return node != null && node.healthy();
    }
}
