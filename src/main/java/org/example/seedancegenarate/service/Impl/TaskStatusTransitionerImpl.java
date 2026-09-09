package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Objects;

/**
 * 任务终态唯一入口实现：直接用 Mapper + 事件发布，避免与 VideoTaskService 循环依赖
 * （VideoTaskServiceImpl 会调用本类）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusTransitionerImpl implements TaskStatusTransitioner {

    private final VideoTaskMapper videoTaskMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AdmissionControl admissionControl;
    private final FailureWalletReleaseService failureWalletReleaseService;

    @Override
    public boolean markFailed(Long videoTaskId, String message) {
        return markFailedInternal(videoTaskId, null, message, "任务失败", false);
    }

    @Override
    public boolean markFailedIfCurrent(VideoTask expectedTask, String message) {
        return markFailedInternal(expectedTask == null ? null : expectedTask.getId(),
                expectedTask, message, "任务失败", false);
    }

    @Override
    public boolean markRecoveryFailedIfCurrent(VideoTask expectedTask, String message) {
        if (expectedTask == null || !"RECOVERY_REQUIRED".equals(expectedTask.getPhase())) {
            return false;
        }
        return markFailedInternal(expectedTask.getId(), expectedTask,
                message, "管理员终止恢复任务", true);
    }

    @Override
    public boolean markTimedOut(Long videoTaskId, String message) {
        return markFailedInternal(videoTaskId, null, message, "任务超时终止", false);
    }

    @Override
    public boolean markTimedOutIfCurrent(VideoTask expectedTask, String message) {
        return markFailedInternal(expectedTask == null ? null : expectedTask.getId(),
                expectedTask, message, "任务超时终止", false);
    }

    @Override
    public String statusOf(Long videoTaskId) {
        VideoTask task = videoTaskMapper.selectById(videoTaskId);
        return task == null ? null : task.getStatus();
    }

    @Override
    public VideoTask findById(Long videoTaskId) {
        return videoTaskMapper.selectById(videoTaskId);
    }

    private boolean markFailedInternal(Long videoTaskId, VideoTask expectedTask,
                                       String message, String logLabel,
                                       boolean allowRecoveryRequired) {
        if (videoTaskId == null) {
            return false;
        }
        VideoTask task = videoTaskMapper.selectById(videoTaskId);
        if (task == null || !"PROCESSING".equals(task.getStatus())) {
            return false; // 幂等：不存在或已终态（不覆盖成功结果）
        }
        if (expectedTask != null && (!sameExecutionIdentity(expectedTask, task)
                || (!allowRecoveryRequired && "RECOVERY_REQUIRED".equals(task.getPhase())))) {
            return false;
        }
        String userMsg = toUserErrorMessage(message);
        var update = Wrappers.<VideoTask>lambdaUpdate()
                .eq(VideoTask::getId, videoTaskId)
                .eq(VideoTask::getStatus, "PROCESSING");
        if (expectedTask != null) {
            if (expectedTask.getCurrentAttemptId() == null) {
                update.isNull(VideoTask::getCurrentAttemptId);
            } else {
                update.eq(VideoTask::getCurrentAttemptId, expectedTask.getCurrentAttemptId());
            }
            if (expectedTask.getProviderTaskId() == null) {
                update.isNull(VideoTask::getProviderTaskId);
            } else {
                update.eq(VideoTask::getProviderTaskId, expectedTask.getProviderTaskId());
            }
            if (expectedTask.getPhase() == null) {
                update.isNull(VideoTask::getPhase);
            } else {
                update.eq(VideoTask::getPhase, expectedTask.getPhase());
            }
            if (expectedTask.getRetryCount() == null) {
                update.isNull(VideoTask::getRetryCount);
            } else {
                update.eq(VideoTask::getRetryCount, expectedTask.getRetryCount());
            }
            if (expectedTask.getProvider() == null) {
                update.isNull(VideoTask::getProvider);
            } else {
                update.eq(VideoTask::getProvider, expectedTask.getProvider());
            }
        }
        int rows = videoTaskMapper.update(null, update
                .set(VideoTask::getStatus, "FAILED")
                .set(VideoTask::getErrorMsg, userMsg));
        if (rows == 0) {
            return false; // 并发下其他写入者已落终态
        }
        task.setStatus("FAILED");
        task.setErrorMsg(userMsg);
        // Redis 槽与失败解冻都必须在 outer terminal tx 提交之后：WalletService.release 是 REQUIRED，
        // 若在共享 tx 内抛错，即使这里 catch 住也可能已把 tx 标 rollback-only，形成无限租约接管。
        releaseFailureSideEffectsAfterCommit(task);
        eventPublisher.publishEvent(new TaskStatusChangedEvent(
                task.getUserId(),
                new TaskStatusChangedEvent.Message(
                        task.businessTaskId(),
                        task.getStatus(),
                        task.getVideoUrl(),
                        task.getOutputType(),
                        task.getErrorMsg(),
                        task.getCostAmount()
                )
        ));
        log.warn("{}: taskId={}, provider={}, reason={}",
                logLabel, task.businessTaskId(), task.getProvider(), userMsg);
        return true;
    }

    /** Redis/钱包副作用只在终态提交后执行；失败由现有账务/槽位对账补偿。 */
    private void releaseFailureSideEffectsAfterCommit(VideoTask task) {
        Runnable release = () -> {
            admissionControl.releaseQuietly(task.getUserId(), task.getId(), task.getApiKeyId());
            try {
                failureWalletReleaseService.release(task);
            } catch (Exception e) {
                log.warn("任务失败解冻暂未完成，等待账务补偿: taskId={}, err={}",
                        task.businessTaskId(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    release.run();
                }
            });
            return;
        }
        release.run();
    }

    private boolean sameExecutionIdentity(VideoTask expected, VideoTask actual) {
        return Objects.equals(expected.getCurrentAttemptId(), actual.getCurrentAttemptId())
                && Objects.equals(expected.getProviderTaskId(), actual.getProviderTaskId())
                && Objects.equals(expected.getPhase(), actual.getPhase())
                && Objects.equals(expected.getRetryCount(), actual.getRetryCount())
                && Objects.equals(expected.getProvider(), actual.getProvider());
    }

    private String toUserErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.toLowerCase(Locale.ROOT).contains("copyright")
                ? "涉及版权问题，生成失败"
                : message;
    }
}
