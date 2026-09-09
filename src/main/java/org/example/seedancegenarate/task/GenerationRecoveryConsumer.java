package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 找回 ComfyUI 已接单但响应丢失的 prompt；只绑定，不负责盲目重投。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationRecoveryConsumer implements AsyncJobHandler {
    private static final long LEASE_SECONDS = 30;

    private final AsyncJobService asyncJobService;
    private final GenerationAttemptService attemptService;
    private final ObjectMapper objectMapper;

    @Override
    public String jobType() {
        return GenerationAttemptService.RECOVERY_JOB_TYPE;
    }

    @Override
    public long leaseSeconds() {
        return LEASE_SECONDS;
    }

    @Override
    public void execute(AsyncJob job) {
        GenerationAttemptService.JobPayload payload = parse(job.getPayload());
        if (payload == null || payload.attemptId() == null || payload.attemptId() <= 0) {
            asyncJobService.complete(job);
            return;
        }
        try {
            GenerationAttemptService.RecoveryResult result = attemptService.recover(payload.attemptId());
            if (result == GenerationAttemptService.RecoveryResult.MANUAL_REQUIRED) {
                log.warn("生成提交无法自动找回，等待管理员处置: attemptId={}", payload.attemptId());
            }
            asyncJobService.complete(job);
        } catch (Exception e) {
            String reason = safeMessage(e);
            log.warn("生成提交恢复查询失败，按持久化作业退避: attemptId={}, reason={}",
                    payload.attemptId(), reason);
            asyncJobService.failAndRetry(job, reason);
        }
    }

    private GenerationAttemptService.JobPayload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, GenerationAttemptService.JobPayload.class);
        } catch (Exception e) {
            log.warn("解析生成恢复作业参数失败: {}", payload);
            return null;
        }
    }

    private String safeMessage(Exception error) {
        String message = StringUtils.hasText(error.getMessage())
                ? error.getMessage() : error.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
