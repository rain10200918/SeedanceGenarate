package org.example.seedancegenarate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.VideoCompletionProperties;
import org.example.seedancegenarate.engine.CompletionMechanism;
import org.example.seedancegenarate.engine.GenerateCommand;
import org.example.seedancegenarate.engine.GenerationMode;
import org.example.seedancegenarate.engine.OutputType;
import org.example.seedancegenarate.engine.SubmissionNotAcceptedException;
import org.example.seedancegenarate.engine.SubmitResult;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.GenerationAttempt;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.GenerationAttemptMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** generation_attempt 的唯一状态迁移边界。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationAttemptService {

    public static final String JOB_TYPE = "GENERATION_SUBMIT";
    public static final String RECOVERY_JOB_TYPE = "GENERATION_RECOVERY";

    private static final String INTERRUPTED_MESSAGE = "Worker 在提交中断，结果未知";
    private static final String EMPTY_TASK_ID_MESSAGE = "供应商提交响应缺少任务 ID";
    private static final String RETRY_EXHAUSTED_MESSAGE = "供应商提交重试已耗尽";

    private final GenerationAttemptMapper attemptMapper;
    private final VideoTaskMapper taskMapper;
    private final VideoEngineRegistry engineRegistry;
    private final ObjectMapper objectMapper;
    private final VideoCompletionProperties completionProperties;
    private final TransactionTemplate transactionTemplate;
    private final AsyncJobService asyncJobService;
    @org.springframework.beans.factory.annotation.Autowired
    private StoredImageReferences storedReferences;

    public enum ExecuteResult {
        SUBMITTED,
        SAFE_RETRY,
        RECOVERY_REQUIRED
    }

    public enum RecoveryResult {
        RECOVERED,
        MANUAL_REQUIRED,
        OBSOLETE
    }

    public record JobPayload(Long attemptId) {
    }

    public static String jobKey(long attemptId) {
        if (attemptId <= 0) {
            throw new IllegalArgumentException("attemptId 必须为正数");
        }
        return "attempt:" + attemptId;
    }

    /**
     * 将新轮次设为 task 的 current attempt。该方法只写 MySQL，
     * 会加入调用方的任务创建/冻结/job 事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public GenerationAttempt stageCurrentAttempt(VideoTask task, int attemptNo, String requestedNodeId) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("生成任务必须先落库");
        }
        if (!"PROCESSING".equals(task.getStatus())) {
            throw new IllegalStateException("只能为处理中任务创建生成轮次");
        }
        if (!StringUtils.hasText(task.getProvider()) || task.getProvider().trim().length() > 32) {
            throw new IllegalArgumentException("生成提供方不合法");
        }
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo 必须从 1 开始");
        }
        String normalizedNode = normalizeNode(requestedNodeId);
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setVideoTaskId(task.getId());
        attempt.setAttemptNo(attemptNo);
        attempt.setProvider(task.getProvider().trim());
        // 只生成一次，并且 insert 早于任何外部调用。
        attempt.setProviderRequestId(UUID.randomUUID().toString());
        attempt.setRequestedNodeId(normalizedNode);
        attempt.setStatus(GenerationAttempt.STATUS_PENDING);
        if (attemptMapper.insert(attempt) != 1 || attempt.getId() == null) {
            throw new IllegalStateException("创建生成轮次失败");
        }
        if (taskMapper.activateAttempt(task.getId(), task.getCurrentAttemptId(), attempt.getId()) != 1) {
            throw new IllegalStateException("任务当前生成轮次已被其他 Worker 替换");
        }
        task.setCurrentAttemptId(attempt.getId());
        task.setPhase("QUEUED");
        return attempt;
    }

    /**
     * SAFE_RETRY 耗尽时只收口 attempt；任务失败、解冻和并发槽释放由调用方同一终态事务完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finishFailed(long attemptId, String error) {
        GenerationAttempt attempt = requireAttempt(attemptId);
        if (GenerationAttempt.STATUS_FAILED.equals(attempt.getStatus())) {
            return true;
        }
        if (!GenerationAttempt.STATUS_PENDING.equals(attempt.getStatus())) {
            throw new IllegalStateException("只能收口待提交的生成轮次: " + attempt.getStatus());
        }
        String reason = StringUtils.hasText(error) ? error : RETRY_EXHAUSTED_MESSAGE;
        if (attemptMapper.markFailed(attemptId, truncate(reason, 1000)) == 1) {
            return true;
        }
        return GenerationAttempt.STATUS_FAILED.equals(requireAttempt(attemptId).getStatus());
    }

    /**
     * 执行一次已持久化的提交。方法本身不持有事务；结果回写用短事务。
     * 当前 provider 无法按 providerRequestId 恢复，所以遗留 SUBMITTING 一律安全停放。
     */
    public ExecuteResult execute(long attemptId) {
        GenerationAttempt attempt = requireAttempt(attemptId);
        if (GenerationAttempt.STATUS_SUBMITTED.equals(attempt.getStatus())) {
            return ExecuteResult.SUBMITTED;
        }
        if (GenerationAttempt.STATUS_SUBMITTING.equals(attempt.getStatus())) {
            return parkUnknown(attempt, INTERRUPTED_MESSAGE);
        }
        if (GenerationAttempt.STATUS_SUBMIT_UNKNOWN.equals(attempt.getStatus())) {
            taskMapper.markAttemptRecoveryRequired(attempt.getVideoTaskId(), attempt.getId());
            ensureRecoveryScheduled(attempt.getId());
            return ExecuteResult.RECOVERY_REQUIRED;
        }
        if (!GenerationAttempt.STATUS_PENDING.equals(attempt.getStatus())) {
            throw new IllegalStateException("生成轮次不可执行: " + attempt.getStatus());
        }

        VideoTask task;
        VideoEngine engine;
        GenerateCommand command;
        try {
            task = requireCurrentTask(attempt);
            engine = engineRegistry.get(attempt.getProvider());
            command = rebuildCommand(task, attempt, engine);
        } catch (RuntimeException e) {
            log.warn("生成提供方或命令准备失败: attemptId={}, reason={}", attemptId, safeMessage(e));
            return ExecuteResult.SAFE_RETRY; // 尚未进入 SUBMITTING，肯定没有外部副作用
        }
        if (!claimForSubmit(attempt)) {
            return afterClaimRace(requireAttempt(attemptId));
        }

        try {
            SubmitResult result = engine.submit(command,
                    nodeId -> rememberSubmittingNode(attempt.getId(), nodeId));
            if (!validResult(result)) {
                return parkUnknown(attempt, EMPTY_TASK_ID_MESSAGE);
            }
            return recordSubmitted(attempt, result);
        } catch (SubmissionNotAcceptedException e) {
            return markSafeRetry(attempt, safeMessage(e));
        } catch (Exception e) {
            return parkUnknown(attempt, safeMessage(e));
        }
    }

    private ExecuteResult markSafeRetry(GenerationAttempt attempt, String error) {
        Boolean reset = transactionTemplate.execute(status -> {
            if (attemptMapper.markPending(attempt.getId(), error) != 1) {
                return false;
            }
            taskMapper.markAttemptQueued(attempt.getVideoTaskId(), attempt.getId());
            return true;
        });
        if (Boolean.TRUE.equals(reset)) {
            return ExecuteResult.SAFE_RETRY;
        }
        return afterClaimRace(requireAttempt(attempt.getId()));
    }

    private ExecuteResult parkUnknown(GenerationAttempt attempt, String error) {
        Boolean parked = transactionTemplate.execute(status -> {
            if (attemptMapper.markUnknown(attempt.getId(), truncate(error, 1000)) != 1) {
                return false;
            }
            attempt.setStatus(GenerationAttempt.STATUS_SUBMIT_UNKNOWN);
            taskMapper.markAttemptRecoveryRequired(attempt.getVideoTaskId(), attempt.getId());
            // UNKNOWN 事实和恢复作业同事务提交；即使进程随后退出，也不会留下永久无消费者的任务。
            ensureRecoveryScheduled(attempt.getId());
            return true;
        });
        if (Boolean.TRUE.equals(parked)) {
            return ExecuteResult.RECOVERY_REQUIRED;
        }
        GenerationAttempt latest = requireAttempt(attempt.getId());
        return switch (latest.getStatus()) {
            case GenerationAttempt.STATUS_PENDING -> ExecuteResult.SAFE_RETRY;
            case GenerationAttempt.STATUS_SUBMITTED -> ExecuteResult.SUBMITTED;
            case GenerationAttempt.STATUS_SUBMIT_UNKNOWN -> {
                taskMapper.markAttemptRecoveryRequired(latest.getVideoTaskId(), latest.getId());
                ensureRecoveryScheduled(latest.getId());
                yield ExecuteResult.RECOVERY_REQUIRED;
            }
            default -> throw new IllegalStateException(
                    "停放结果与生成轮次状态不一致: " + latest.getStatus());
        };
    }

    /**
     * 提交权不是单独的 attempt CAS：task 必须在同一短事务中仍指向它。
     * task CAS 失败会回滚 PENDING -> SUBMITTING，调用方因此不得发出 HTTP。
     */
    private boolean claimForSubmit(GenerationAttempt attempt) {
        Boolean claimed = transactionTemplate.execute(status -> {
            if (attemptMapper.markSubmitting(attempt.getId()) != 1) {
                return false;
            }
            if (taskMapper.markAttemptSubmitting(attempt.getVideoTaskId(), attempt.getId()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(claimed);
    }

    private ExecuteResult afterClaimRace(GenerationAttempt latest) {
        return switch (latest.getStatus()) {
            case GenerationAttempt.STATUS_SUBMITTED -> ExecuteResult.SUBMITTED;
            case GenerationAttempt.STATUS_PENDING -> ExecuteResult.SAFE_RETRY;
            case GenerationAttempt.STATUS_SUBMITTING -> parkUnknown(latest, INTERRUPTED_MESSAGE);
            case GenerationAttempt.STATUS_SUBMIT_UNKNOWN -> {
                taskMapper.markAttemptRecoveryRequired(latest.getVideoTaskId(), latest.getId());
                ensureRecoveryScheduled(latest.getId());
                yield ExecuteResult.RECOVERY_REQUIRED;
            }
            default -> throw new IllegalStateException("生成轮次不可执行: " + latest.getStatus());
        };
    }

    private ExecuteResult recordSubmitted(GenerationAttempt attempt, SubmitResult result) {
        return transactionTemplate.execute(status -> {
            int changed = attemptMapper.markSubmitted(attempt.getId(),
                    result.getProviderTaskId().trim(), normalizeNode(result.getNodeId()));
            if (changed != 1) {
                GenerationAttempt latest = requireAttempt(attempt.getId());
                if (GenerationAttempt.STATUS_SUBMITTED.equals(latest.getStatus())) {
                    return ExecuteResult.SUBMITTED;
                }
                throw new IllegalStateException("提交结果无法落到当前 attempt");
            }
            int taskChanged = taskMapper.markAttemptSubmitted(attempt.getVideoTaskId(), attempt.getId(),
                    result.getProviderTaskId().trim(), normalizeNode(result.getNodeId()));
            if (taskChanged != 1) {
                // 记住外部已接单的事实，但 current_attempt_id 防止旧轮次覆盖新轮次。
                log.warn("供应商已接单但 attempt 已非任务当前轮次: taskId={}, attemptId={}",
                        attempt.getVideoTaskId(), attempt.getId());
            }
            return ExecuteResult.SUBMITTED;
        });
    }

    /**
     * 只做“查到并绑定”，绝不把查询为空解释成供应商未接单。
     * 查询异常向外抛，由 GENERATION_RECOVERY 的持久化租约退避重试。
     */
    public RecoveryResult recover(long attemptId) throws Exception {
        GenerationAttempt attempt = requireAttempt(attemptId);
        if (GenerationAttempt.STATUS_SUBMITTED.equals(attempt.getStatus())) {
            return RecoveryResult.RECOVERED;
        }
        if (!GenerationAttempt.STATUS_SUBMIT_UNKNOWN.equals(attempt.getStatus())) {
            return RecoveryResult.OBSOLETE;
        }
        VideoTask task = taskMapper.selectById(attempt.getVideoTaskId());
        if (task == null || !"PROCESSING".equals(task.getStatus())
                || !attempt.getId().equals(task.getCurrentAttemptId())) {
            return RecoveryResult.OBSOLETE;
        }
        VideoEngine engine = engineRegistry.get(attempt.getProvider());
        if (!engine.supportsSubmissionRecovery()) {
            return RecoveryResult.MANUAL_REQUIRED;
        }
        String nodeId = StringUtils.hasText(attempt.getNodeId())
                ? attempt.getNodeId().trim() : normalizeNode(attempt.getRequestedNodeId());
        if (!StringUtils.hasText(nodeId) || !StringUtils.hasText(attempt.getProviderRequestId())) {
            return RecoveryResult.MANUAL_REQUIRED;
        }
        SubmitResult found = engine.findSubmission(attempt.getProviderRequestId(), nodeId);
        if (!validResult(found)) {
            return RecoveryResult.MANUAL_REQUIRED;
        }
        ExecuteResult recorded = recordSubmitted(attempt, found);
        return recorded == ExecuteResult.SUBMITTED
                ? RecoveryResult.RECOVERED : RecoveryResult.MANUAL_REQUIRED;
    }

    /** 管理员已从节点日志/历史确认 promptId 后，严格绑定当前 UNKNOWN attempt。 */
    public boolean bindRecovered(long attemptId, String providerTaskId, String nodeId) {
        String promptId = requireText(providerTaskId, "promptId", 128);
        String normalizedNode = normalizeNode(nodeId);
        if (normalizedNode == null) {
            throw new IllegalArgumentException("节点 ID 不能为空");
        }
        GenerationAttempt attempt = requireAttempt(attemptId);
        if (!GenerationAttempt.STATUS_SUBMIT_UNKNOWN.equals(attempt.getStatus())) {
            return GenerationAttempt.STATUS_SUBMITTED.equals(attempt.getStatus())
                    && promptId.equals(attempt.getProviderTaskId())
                    && normalizedNode.equals(attempt.getNodeId());
        }
        VideoTask task = taskMapper.selectById(attempt.getVideoTaskId());
        if (task == null || !"PROCESSING".equals(task.getStatus())
                || !"RECOVERY_REQUIRED".equals(task.getPhase())
                || !attempt.getId().equals(task.getCurrentAttemptId())) {
            return false;
        }
        Boolean bound = transactionTemplate.execute(status -> {
            if (attemptMapper.markSubmitted(attemptId, promptId, normalizedNode) != 1) {
                return false;
            }
            if (taskMapper.markAttemptSubmitted(task.getId(), attemptId, promptId, normalizedNode) != 1) {
                throw new IllegalStateException("任务已被其他处理流程更新");
            }
            return true;
        });
        return Boolean.TRUE.equals(bound);
    }

    /** 为 UNKNOWN attempt 补一张唯一恢复作业；同 key 活跃时 ODKU 保持 no-op。 */
    public void ensureRecoveryScheduled(long attemptId) {
        GenerationAttempt attempt = requireAttempt(attemptId);
        if (!GenerationAttempt.STATUS_SUBMIT_UNKNOWN.equals(attempt.getStatus())) {
            return;
        }
        VideoEngine engine;
        try {
            engine = engineRegistry.get(attempt.getProvider());
        } catch (RuntimeException e) {
            log.warn("生成恢复暂无法解析提供方: attemptId={}, provider={}",
                    attemptId, attempt.getProvider());
            return;
        }
        if (engine == null || !engine.supportsSubmissionRecovery()) {
            return;
        }
        asyncJobService.enqueue(RECOVERY_JOB_TYPE, jobKey(attemptId), recoveryPayload(attemptId));
    }

    private String recoveryPayload(long attemptId) {
        try {
            return objectMapper.writeValueAsString(new JobPayload(attemptId));
        } catch (Exception e) {
            throw new IllegalStateException("序列化生成恢复作业失败", e);
        }
    }

    private GenerateCommand rebuildCommand(VideoTask task, GenerationAttempt attempt, VideoEngine engine) {
        List<String> images = parseList(task.getImages());
        if(StringUtils.hasText(task.getStoredImageReferences())) {
            if(storedReferences==null || !images.isEmpty()) throw new IllegalStateException("内部参考图片无效");
            try {
                var refs=objectMapper.readValue(task.getStoredImageReferences(),new com.fasterxml.jackson.core.type.TypeReference<List<StoredImageReferences.Reference>>(){});
                images=storedReferences.signedUrls(task.getUserId(),refs);
            } catch(Exception e) { throw new IllegalStateException("参考图片已不可用",e); }
        }
        List<String> videos = parseList(task.getReferenceVideos());
        List<String> audios = parseList(task.getReferenceAudios());
        OutputType outputType;
        try {
            outputType = OutputType.valueOf(task.getOutputType());
        } catch (Exception ignored) {
            outputType = engine.outputType(task.getModel());
        }
        return GenerateCommand.builder()
                .mode(GenerationMode.of(!images.isEmpty(), outputType))
                .imageUrls(images)
                .videoUrls(videos)
                .audioUrls(audios)
                .prompt(task.getPrompt())
                .duration(task.getDuration())
                .ratio(task.getRatio())
                .model(task.getModel())
                .megapixels(task.getMegapixels())
                .webhookUrl(resolveWebhookUrl(engine, attempt.getProvider()))
                .providerRequestId(attempt.getProviderRequestId())
                .nodeId(attempt.getRequestedNodeId())
                .build();
    }

    private void rememberSubmittingNode(long attemptId, String nodeId) {
        String normalized = normalizeNode(nodeId);
        if (normalized == null) {
            throw new IllegalArgumentException("实际提交节点不能为空");
        }
        if (attemptMapper.rememberSubmittingNode(attemptId, normalized) == 1) {
            // 本次 execute 后续异常停放时直接复用，不再依赖一次额外重读。
            GenerationAttempt latest = requireAttempt(attemptId);
            latest.setNodeId(normalized);
            return;
        }
        GenerationAttempt latest = requireAttempt(attemptId);
        if (!GenerationAttempt.STATUS_SUBMITTING.equals(latest.getStatus())
                || !normalized.equals(latest.getNodeId())) {
            throw new IllegalStateException("生成轮次已失去实际节点写入权");
        }
    }

    private String resolveWebhookUrl(VideoEngine engine, String provider) {
        if (engine.completionMechanism() != CompletionMechanism.CALLBACK) {
            return null;
        }
        String base = completionProperties.getCallbackBaseUrl();
        String secret = completionProperties.getCallbackSecret();
        if (!StringUtils.hasText(base) || !StringUtils.hasText(secret)) {
            return null;
        }
        return base.replaceAll("/+$", "") + "/api/callback/" + provider
                + "?token=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    }

    private List<String> parseList(String value) {
        if (!StringUtils.hasText(value)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("任务参考素材快照损坏", e);
        }
    }

    private GenerationAttempt requireAttempt(long attemptId) {
        GenerationAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new IllegalArgumentException("生成轮次不存在: " + attemptId);
        }
        return attempt;
    }

    private VideoTask requireCurrentTask(GenerationAttempt attempt) {
        VideoTask task = taskMapper.selectById(attempt.getVideoTaskId());
        if (task == null || !"PROCESSING".equals(task.getStatus())) {
            throw new IllegalStateException("生成任务已不可提交");
        }
        if (!attempt.getId().equals(task.getCurrentAttemptId())) {
            throw new IllegalStateException("生成轮次已非任务当前轮次");
        }
        if (!attempt.getProvider().equals(task.getProvider())) {
            throw new IllegalStateException("生成轮次与任务提供方不一致");
        }
        return task;
    }

    private boolean validResult(SubmitResult result) {
        return result != null
                && StringUtils.hasText(result.getProviderTaskId())
                && result.getProviderTaskId().trim().length() <= 128
                && (result.getNodeId() == null || result.getNodeId().trim().length() <= 64);
    }

    private String normalizeNode(String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return null;
        }
        String normalized = nodeId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("节点 ID 不能超过 64 个字符");
        }
        return normalized;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return truncate(StringUtils.hasText(message) ? message : error.getClass().getSimpleName(), 1000);
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String requireText(String value, String label, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
