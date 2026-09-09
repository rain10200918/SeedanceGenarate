package org.example.seedancegenarate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.dto.AdminTaskRecoveryView;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** RECOVERY_REQUIRED 的管理员诊断与两种安全处置；不提供“盲目重投”。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTaskRecoveryService {
    private static final String DEFAULT_TERMINATE_REASON = "管理员确认远端任务无法恢复";

    private final VideoTaskMapper taskMapper;
    private final GenerationAttemptMapper attemptMapper;
    private final AsyncJobService asyncJobService;
    private final GenerationAttemptService attemptService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final VideoEngineRegistry engineRegistry;

    /**
     * 管理员点「再查一次」的结果。{@code message} 是给运营看的整句人话：
     * 只出现节点名，不出现 attempt / 请求号 / 异常文本（那些在「技术细节」里）。
     */
    public record LookupResult(boolean found, boolean settled, String message) {
        static LookupResult foundOn(String nodeId) {
            return new LookupResult(true, false, "在节点 " + nodeId + " 上找到了，任务已接回，会继续生成。");
        }

        static LookupResult notFound(String nodeId) {
            return new LookupResult(false, false,
                    "节点 " + nodeId + " 上没有这条任务的记录，大概率没接到单或节点重启后丢了。可以判定失败，把算力退回用户。");
        }

        static LookupResult unreachable(String nodeId) {
            return new LookupResult(false, false, "现在连不上节点 " + nodeId + "，没法查。等节点恢复后再点一次。");
        }

        static LookupResult noTrace() {
            return new LookupResult(false, false, "提交时没记下是哪台节点接的，系统没法去查。只能判定失败，把算力退回用户。");
        }

        static LookupResult unsupported() {
            return new LookupResult(false, false, "这类任务的服务商不支持找回。只能判定失败，把算力退回用户。");
        }

        static LookupResult alreadyHandled() {
            return new LookupResult(false, true, "这条任务已经处理过了，刷新一下列表。");
        }
    }

    /**
     * 现在就去节点上找一次；找到就接回。走的是自动恢复同一段代码（按请求号查、查到才绑定、查不到绝不重投），
     * 和后台作业并发也安全：绑定是 CAS，谁先谁赢，后到的拿到 RECOVERED / OBSOLETE。
     */
    public LookupResult lookup(String businessTaskId) {
        VideoTask task = requireTask(businessTaskId);
        if (!inRecovery(task)) {
            return LookupResult.alreadyHandled();
        }
        GenerationAttempt attempt = requireCurrentAttempt(task);
        String nodeId = nodeOf(attempt);
        if (!supportsRecovery(task.getProvider())) {
            return LookupResult.unsupported();
        }
        if (!StringUtils.hasText(nodeId) || !StringUtils.hasText(attempt.getProviderRequestId())) {
            return LookupResult.noTrace();
        }
        try {
            return switch (attemptService.recover(attempt.getId())) {
                case RECOVERED -> LookupResult.foundOn(nodeId);
                case MANUAL_REQUIRED -> LookupResult.notFound(nodeId);
                case OBSOLETE -> LookupResult.alreadyHandled();
            };
        } catch (Exception e) {
            log.warn("管理员再查一次失败: task={}, node={}, err={}", task.businessTaskId(), nodeId, e.getMessage());
            return LookupResult.unreachable(nodeId);
        }
    }

    private boolean supportsRecovery(String provider) {
        try {
            VideoEngine engine = engineRegistry.get(provider);
            return engine.supportsSubmissionRecovery();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean inRecovery(VideoTask task) {
        return "PROCESSING".equals(task.getStatus())
                && "RECOVERY_REQUIRED".equals(task.getPhase())
                && task.getCurrentAttemptId() != null;
    }

    private static String nodeOf(GenerationAttempt attempt) {
        return StringUtils.hasText(attempt.getNodeId()) ? attempt.getNodeId() : attempt.getRequestedNodeId();
    }

    public AdminTaskRecoveryView find(String businessTaskId) {
        VideoTask task = requireTask(businessTaskId);
        GenerationAttempt attempt = requireCurrentAttempt(task);
        AsyncJob job = asyncJobService.find(GenerationAttemptService.RECOVERY_JOB_TYPE,
                GenerationAttemptService.jobKey(attempt.getId()));
        String nodeId = nodeOf(attempt);
        return new AdminTaskRecoveryView(
                task.getId(), task.businessTaskId(), task.getStatus(), task.getPhase(), task.getProvider(),
                task.getFreezeAmount(), attempt.getId(), attempt.getStatus(), nodeId,
                attempt.getProviderRequestId(), attempt.getProviderTaskId(), attempt.getLastError(),
                attempt.getUpdateTime(), toJob(job));
    }

    @Transactional(rollbackFor = Exception.class)
    public void bind(String businessTaskId, String promptId, String nodeId) {
        VideoTask task = requireRecoveryTask(businessTaskId);
        if (!attemptService.bindRecovered(task.getCurrentAttemptId(), promptId, nodeId)) {
            throw BusinessException.conflict("任务已经被其他恢复流程处理，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void terminate(String businessTaskId, String reason) {
        VideoTask task = requireRecoveryTask(businessTaskId);
        GenerationAttempt attempt = requireCurrentAttempt(task);
        String message = normalizeReason(reason);
        if (!taskStatusTransitioner.markRecoveryFailedIfCurrent(task, message)) {
            throw BusinessException.conflict("任务已经被其他恢复流程处理，请刷新后重试");
        }
        if (attemptMapper.markUnknownFailed(attempt.getId(), message) != 1) {
            throw BusinessException.conflict("生成轮次已经被其他恢复流程处理，请刷新后重试");
        }
    }

    private VideoTask requireRecoveryTask(String businessTaskId) {
        VideoTask task = requireTask(businessTaskId);
        if (!"PROCESSING".equals(task.getStatus())
                || !"RECOVERY_REQUIRED".equals(task.getPhase())
                || task.getCurrentAttemptId() == null) {
            throw BusinessException.conflict("该任务当前不需要提交恢复");
        }
        return task;
    }

    private VideoTask requireTask(String businessTaskId) {
        if (!StringUtils.hasText(businessTaskId) || businessTaskId.trim().length() > 128) {
            throw BusinessException.badRequest("任务 ID 不合法");
        }
        VideoTask task = taskMapper.findByBusinessTaskId(businessTaskId.trim());
        if (task == null) {
            throw BusinessException.notFound("任务不存在");
        }
        return task;
    }

    private GenerationAttempt requireCurrentAttempt(VideoTask task) {
        if (task.getCurrentAttemptId() == null) {
            throw BusinessException.conflict("任务缺少当前生成轮次，无法自动处置");
        }
        GenerationAttempt attempt = attemptMapper.selectById(task.getCurrentAttemptId());
        if (attempt == null || !task.getId().equals(attempt.getVideoTaskId())) {
            throw BusinessException.conflict("任务生成轮次数据不完整");
        }
        return attempt;
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return DEFAULT_TERMINATE_REASON;
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw BusinessException.badRequest("终止原因不能超过 500 个字符");
        }
        return normalized;
    }

    private AdminTaskRecoveryView.RecoveryJob toJob(AsyncJob job) {
        return job == null ? null : new AdminTaskRecoveryView.RecoveryJob(
                job.getStatus(), job.getAttempts(), job.getMaxAttempts(), job.getLastError(),
                job.getAvailableAt(), job.getLeaseUntil());
    }
}
