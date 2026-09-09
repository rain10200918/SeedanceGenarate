package org.example.seedancegenarate.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.GenerationAttemptService;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** 领取持久化生成提交作业；只有这里可以调用 {@link GenerationAttemptService#execute(long)}。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationSubmitConsumer implements AsyncJobHandler {
    private static final long LEASE_SECONDS = 300;
    private static final String SAFE_RETRY_ERROR = "供应商明确未接单，等待安全重试";

    private final AsyncJobService asyncJobService;
    private final GenerationAttemptService attemptService;
    private final GenerationAttemptMapper attemptMapper;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String jobType() {
        return GenerationAttemptService.JOB_TYPE;
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
        long attemptId = payload.attemptId();
        if (finishIfTaskAlreadyTerminal(job, attemptId)) {
            return;
        }
        GenerationAttemptService.ExecuteResult result;
        try {
            result = attemptService.execute(attemptId);
        } catch (Exception e) {
            // execute 已把所有供应商 HTTP 结果分类；这里只处理基础设施/代码异常。
            log.warn("执行生成提交作业失败: jobId={}, attemptId={}, reason={}",
                    job.getId(), attemptId, e.getMessage());
            if (!lastAttempt(job)) {
                asyncJobService.failAndRetry(job, safeMessage(e));
            } else if (asyncJobService.renew(job, LEASE_SECONDS)) {
                // generic 异常可能发生在供应商已接单、DB 尚未回写之后；最后一次也不能伪 DEAD/失败。
                log.warn("提交结果不确定且重试已耗尽，保留 RUNNING 等待租约接管: jobId={}, attemptId={}",
                        job.getId(), attemptId);
            }
            return;
        }
        if (result == GenerationAttemptService.ExecuteResult.SAFE_RETRY) {
            retrySafely(job, attemptId);
            return;
        }
        // SUBMITTED 与 RECOVERY_REQUIRED 都不再投递；后者由人工恢复流程接管。
        asyncJobService.complete(job);
    }

    private void retrySafely(AsyncJob job, long attemptId) {
        int attempts = job.getAttempts() == null ? 0 : Math.max(job.getAttempts(), 0);
        int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(job.getMaxAttempts(), 1);
        if (attempts + 1 < maxAttempts) {
            asyncJobService.failAndRetry(job, SAFE_RETRY_ERROR);
            return;
        }
        // 最后一次不先把 job 打成 DEAD：否则两步之间崩溃会留下 DEAD + PROCESSING，再无 Worker 可领。
        // 先续租确认 ownership，再终态任务，最后收 attempt/job；中间崩溃可由过期租约重放收口。
        transactionTemplate.executeWithoutResult(ignored -> {
            // renew UPDATE 会锁住 async_job 行直到本事务提交；新 generation claim 只能在之后发生。
            if (!asyncJobService.renew(job, LEASE_SECONDS)) {
                return;
            }
            GenerationAttempt attempt = attemptMapper.selectById(attemptId);
            if (attempt == null || attempt.getVideoTaskId() == null) {
                log.error("生成提交安全重试耗尽但 attempt 不存在: attemptId={}", attemptId);
                completeOwned(job);
                return;
            }
            VideoTask expectedTask = taskStatusTransitioner.findById(attempt.getVideoTaskId());
            if (expectedTask == null || !attempt.getId().equals(expectedTask.getCurrentAttemptId())) {
                finishObsoleteAttempt(attempt);
                completeOwned(job);
                return;
            }
            boolean transitioned = taskStatusTransitioner.markFailedIfCurrent(expectedTask, SAFE_RETRY_ERROR);
            VideoTask current = taskStatusTransitioner.findById(attempt.getVideoTaskId());
            if (transitioned || current == null || "FAILED".equals(current.getStatus())) {
                attemptService.finishFailed(attemptId, SAFE_RETRY_ERROR);
                completeOwned(job);
            } else if ("SUCCESS".equals(current.getStatus())) {
                completeOwned(job);
            } else if (!attempt.getId().equals(current.getCurrentAttemptId())) {
                finishObsoleteAttempt(attempt);
                completeOwned(job);
            }
        });
    }

    private void completeOwned(AsyncJob job) {
        if (!asyncJobService.complete(job)) {
            throw new IllegalStateException("生成提交作业租约无法完成");
        }
    }

    /** 收敛“任务终态已提交，attempt/job 尚未收口”的崩溃窗口。 */
    private boolean finishIfTaskAlreadyTerminal(AsyncJob job, long attemptId) {
        GenerationAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || attempt.getVideoTaskId() == null) {
            // DB 查询失败会抛异常；明确的 null 是无法恢复的孤儿 payload，直接 fenced complete。
            asyncJobService.complete(job);
            return true;
        }
        VideoTask task = taskStatusTransitioner.findById(attempt.getVideoTaskId());
        if (task == null) {
            asyncJobService.complete(job);
            return true;
        }
        if (!attempt.getId().equals(task.getCurrentAttemptId())) {
            finishObsoleteAttempt(attempt);
            asyncJobService.complete(job);
            return true;
        }
        String taskStatus = task.getStatus();
        if ("FAILED".equals(taskStatus) && GenerationAttempt.STATUS_PENDING.equals(attempt.getStatus())) {
            attemptService.finishFailed(attemptId, SAFE_RETRY_ERROR);
            asyncJobService.complete(job);
            return true;
        }
        if ("FAILED".equals(taskStatus) || "SUCCESS".equals(taskStatus)) {
            asyncJobService.complete(job);
            return true;
        }
        if ("RECOVERY_REQUIRED".equals(task.getPhase())) {
            asyncJobService.complete(job);
            return true;
        }
        return false;
    }

    private void finishObsoleteAttempt(GenerationAttempt attempt) {
        if (GenerationAttempt.STATUS_PENDING.equals(attempt.getStatus())) {
            attemptService.finishFailed(attempt.getId(), "生成轮次已被更新轮次替代");
        }
    }

    private boolean lastAttempt(AsyncJob job) {
        int attempts = job.getAttempts() == null ? 0 : Math.max(job.getAttempts(), 0);
        int maxAttempts = job.getMaxAttempts() == null ? 1 : Math.max(job.getMaxAttempts(), 1);
        return attempts + 1 >= maxAttempts;
    }

    private GenerationAttemptService.JobPayload parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, GenerationAttemptService.JobPayload.class);
        } catch (Exception e) {
            log.warn("解析生成提交作业参数失败: {}", payload);
            return null;
        }
    }

    private String safeMessage(Exception e) {
        String message = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
