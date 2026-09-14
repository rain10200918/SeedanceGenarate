package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.AsyncJobService;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.PricingService;
import org.example.seedancegenarate.service.AdmissionControl;
import org.example.seedancegenarate.service.TaskEtaService;
import org.example.seedancegenarate.service.TaskRetryPolicy;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.service.WalletService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoTaskService {
    @Override
    public org.example.seedancegenarate.dto.TaskCallerView getCaller(Long id) {
        return id == null ? null : baseMapper.selectCaller(id);
    }

    private static final int MAX_REMOTE_VIDEO_URL_LENGTH = 16_384;
    /** 终态收尾作业类型；payload: {"videoTaskId":..,"remoteVideoUrl":".."} */
    public static final String JOB_TYPE_TASK_FINALIZE = "TASK_FINALIZE";

    private final VideoDownloadService videoDownloadService;
    private final CostRecordService costRecordService;
    private final ApplicationEventPublisher eventPublisher;
    private final AsyncJobService asyncJobService;
    private final TaskEtaService taskEtaService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final TaskRetryPolicy taskRetryPolicy;
    private final WalletService walletService;
    private final PricingService pricingService;
    private final AdmissionControl admissionControl;
    private final ObjectMapper objectMapper;
    /** 显式事务：不用 @Transactional 抽方法——同类内自调用会绕过代理，事务会静默消失 */
    private final TransactionTemplate transactionTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private org.example.seedancegenarate.service.BillingAuthorizationService billingAuthorization;

    /** 每一轮 attempt 有独立终态作业，旧轮次完成不得吞掉新轮次的 SUCCESS。 */
    public static String finalizeJobKey(Long videoTaskId, Long attemptId) {
        return "task:" + videoTaskId + ":attempt:" + (attemptId == null ? "legacy" : attemptId);
    }

    /** 超时自动重试作业类型（幂等键同为 task:{id}，与终态作业共用 biz_key 无冲突——唯一键是 job_type+biz_key） */
    public static final String JOB_TYPE_TASK_RETRY = "TASK_RETRY";

    @Override
    public java.util.List<VideoTask> findTerminalMissingWalletTransition(int limit,
                                                                        java.util.Collection<Long> excludeIds) {
        return baseMapper.findTerminalMissingWalletTransition(
                Math.min(Math.max(limit, 1), 500), excludeIds);
    }

    @Override
    public VideoTask getByProviderTaskId(String provider, String providerTaskId) {
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(providerTaskId)) {
            return null;
        }
        return this.getOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getProvider, provider.trim())
                .eq(VideoTask::getProviderTaskId, providerTaskId)
                .last("limit 1"), false);
    }

    @Override
    public void updateStatus(VideoTask task, RemoteStatus status) {
        if (task == null || status == null || task.getId() == null) {
            return;
        }
        switch (status.getState()) {
            case SUCCESS -> {
                if ("SUCCESS".equals(task.getStatus())) {
                    return; // 幂等：已完成，勿重复入队
                }
                String remoteVideoUrl = status.getRemoteVideoUrl();
                if (!StringUtils.hasText(remoteVideoUrl)) {
                    throw new IllegalArgumentException("供应商成功结果缺少产物地址");
                }
                if (remoteVideoUrl.length() > MAX_REMOTE_VIDEO_URL_LENGTH) {
                    throw new IllegalArgumentException("供应商产物地址过长");
                }
                // 先以 attempt/provider/phase/retry 身份 CAS 进入 FINALIZING，再在同一短事务入队。
                // TASK_RETRY 只接受 RUNNING，所以一旦已知成功，重投不可能越过这道门。
                Boolean staged = transactionTemplate.execute(tx -> stageFinalize(task, remoteVideoUrl));
                if (Boolean.TRUE.equals(staged)) {
                    // 签名 URL 常含 token/signature，日志只记身份，不打印完整地址。
                    log.info("任务完成，已入队终态作业: taskId={}, attemptId={}, provider={}",
                            task.businessTaskId(), task.getCurrentAttemptId(), task.getProvider());
                }
            }
            case FAILED -> {
                if (StringUtils.hasText(task.getPhase()) && !"RUNNING".equals(task.getPhase())) {
                    return; // FINALIZING=已知成功获胜；RECOVERY_REQUIRED/QUEUED 也不接收旧远端失败。
                }
                taskStatusTransitioner.markFailedIfCurrent(task, status.getErrorMsg());
            }
            case LOST -> {
                // 作业在远端消失（节点重启清空了内存队列）：不是用户的失败，立刻重投而不是等超龄
                if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                    return; // 已终态，忽略迟到的丢失判定
                }
                if (StringUtils.hasText(task.getPhase()) && !"RUNNING".equals(task.getPhase())) {
                    return;
                }
                taskRetryPolicy.retryOrFail(task, status.getErrorMsg());
            }
            default -> {
                // PROCESSING：任务初始即为 PROCESSING，无需落库
            }
        }
    }

    /**
     * 终态收尾。<b>下载/转存在事务之外，落库+计费+结算+事件在一个短事务里。</b>
     * <p>
     * 改动前整个方法一个 {@code @Transactional}：一个大视频几十秒的「HTTP 拉取 → 落临时文件
     * → 上传 OSS」全程占着一条数据库连接什么也不干，MySQL 侧还挂着一条长事务（拖住 undo/purge）。
     * 终态 Worker 现在可在有界槽位内并行；因此更必须把长时间网络 I/O 放在事务外，
     * 避免并发转存按槽位数长期占满数据库连接。
     * <p>
     * 用 {@link TransactionTemplate} 而不是把后半段抽成 {@code @Transactional} 方法：
     * 同类内自调用会绕过代理，<b>事务会静默消失</b>——那是这条路径上最不能出的错。
     */
    @Override
    public void finalizeTask(VideoTask expectedTask, String remoteVideoUrl) throws Exception {
        finalizeTask(expectedTask, remoteVideoUrl, null, 0);
    }

    @Override
    public void finalizeTask(VideoTask expectedTask, String remoteVideoUrl,
                             AsyncJob lease, long leaseSeconds) throws Exception {
        Long videoTaskId = expectedTask == null ? null : expectedTask.getId();
        VideoTask task = videoTaskId == null ? null : this.getById(videoTaskId);
        if (task == null || !"PROCESSING".equals(task.getStatus())
                || "RECOVERY_REQUIRED".equals(task.getPhase())
                || !sameExecutionIdentity(expectedTask, task)) {
            log.info("终态收尾幂等跳过（已终态、不存在或 attempt 已变化）: videoTaskId={}", videoTaskId);
            completeObsoleteLease(lease, leaseSeconds);
            return;
        }
        log.info("开始终态收尾（下载 → OSS）: videoTaskId={}, attemptId={}, provider={}",
                videoTaskId, task.getCurrentAttemptId(), task.getProvider());
        // 两类提供方统一转存 OSS：Seedance 地址会过期，ComfyUI /view 又是内网节点地址。
        // 并发安全：objectKey 由 bizTaskId + attemptId 决定；同轮重放覆盖同一对象，跨轮隔离。
        // 谁能把该轮产物落到任务真相仍由下面的身份 CAS 决定，只有一个赢。
        VideoDownloadService.DownloadedArtifact downloaded = videoDownloadService.download(
                remoteVideoUrl, task.businessTaskId(), task.getCurrentAttemptId(), task.getProvider());
        Boolean committed = transactionTemplate.execute(status -> {
            if (lease != null && !asyncJobService.renew(lease, leaseSeconds)) {
                return null;
            }
            boolean won = commitTerminal(task, downloaded);
            if (lease != null && !asyncJobService.complete(lease)) {
                throw new IllegalStateException("终态已处理但作业租约无法完成");
            }
            return won;
        });
        if (!Boolean.TRUE.equals(committed)) {
            return; // 丢失 lease 或其他写入者已落终态
        }
        // 归还并发槽位。**必须在事务提交之后**：Redis 加不进 MySQL 事务，放事务里一旦回滚
        // 就是「槽位放了但任务还是 PROCESSING」= 超发，而且没人发现；放事务外最坏是少发一路，
        // 对账 2 秒内会补回来。少发能自愈，超发不能。（D-027 同一条边界）
        // 只有 CAS 赢家走到这里，所以不会重复释放。
        admissionControl.releaseQuietly(task.getUserId(), task.getId(), task.getApiKeyId());
        // 事务外：只是刷该模型平均耗时的 Redis 缓存（ETA 用）。
        // 它失败不该把一笔已经结算完的成功任务回滚掉。
        try {
            taskEtaService.refreshAvgDuration(task.getModel());
        } catch (Exception e) {
            log.warn("刷新模型平均耗时缓存失败（不影响任务终态）: model={}, reason={}",
                    task.getModel(), e.getMessage());
        }
        log.info("任务终态落库成功: taskId={}, mediaName={}, size={}",
                task.businessTaskId(), downloaded.mediaName(), downloaded.artifact().contentLength());
    }

    private void completeObsoleteLease(AsyncJob lease, long leaseSeconds) {
        if (lease == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            if (asyncJobService.renew(lease, leaseSeconds)) {
                asyncJobService.complete(lease);
            }
        });
    }

    /** 落库 + 计费 + 结算 + 事件：必须同事务。返回 false = CAS 抢输，本 Worker 什么都不做。 */
    private boolean commitTerminal(VideoTask task, VideoDownloadService.DownloadedArtifact downloaded) {
        Long videoTaskId = task.getId();
        String mediaName = downloaded.mediaName();
        task.setStatus("SUCCESS");
        // 保持既有前端契约：videoUrl 是后端媒体路由的文件标识，而非 OSS key/签名 URL。
        task.setVideoUrl(mediaName);
        task.setArtifactStorageType("OSS");
        task.setArtifactKey(downloaded.artifact().objectKey());
        task.setArtifactContentType(downloaded.artifact().contentType());
        task.setArtifactSize(downloaded.artifact().contentLength());
        task.setArtifactEtag(downloaded.artifact().etag());
        task.setErrorMsg(null);
        // CAS：只有仍处于 PROCESSING 的任务能进 SUCCESS，防止并发终态双写
        LambdaUpdateWrapper<VideoTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(VideoTask::getId, videoTaskId)
                .eq(VideoTask::getStatus, "PROCESSING");
        appendExecutionIdentity(wrapper, task);
        wrapper.set(VideoTask::getStatus, "SUCCESS")
                .set(VideoTask::getVideoUrl, mediaName)
                .set(VideoTask::getArtifactStorageType, "OSS")
                .set(VideoTask::getArtifactKey, downloaded.artifact().objectKey())
                .set(VideoTask::getArtifactContentType, downloaded.artifact().contentType())
                .set(VideoTask::getArtifactSize, downloaded.artifact().contentLength())
                .set(VideoTask::getArtifactEtag, downloaded.artifact().etag())
                .set(VideoTask::getErrorMsg, null);
        if (!this.update(wrapper)) {
            return false; // 其他 Worker 已落终态，幂等跳过
        }
        // 成功计费：仅「成功才计费」的提供方（如 ComfyUI）真正落账，且幂等
        costRecordService.recordOnSuccess(task);
        // 成功结算（预授权扣款）：冻结转消费，动 frozen 永不失败；幂等（biz_key=task:{id}:settle）。
        // 金额用提交时快照（freeze_amount），不用实时价——价格可变、冻结是历史事实
        BigDecimal settleAmount = task.getFreezeAmount() != null ? task.getFreezeAmount()
                : pricingService.price(task).amount();
        if (task.getApiKeyId() == null) {
            walletService.settle(task.getUserId(), settleAmount, task.getId());
        } else {
            billingAuthorization.settle(task, settleAmount);
        }
        // 留在事务内：CanvasEventListener / PipelineEventListener 是裸 @EventListener（同步立即执行），
        // 它们的节点回填写入现在就在这个事务里，挪出去会改变画布回填与终态的原子性。
        publishStatusChanged(task);
        return true;
    }

    /** SUCCESS 与 finalize job 必须同一事务，避免只落 FINALIZING 或只入队。 */
    private boolean stageFinalize(VideoTask expected, String remoteVideoUrl) {
        if (expected == null || expected.getId() == null || !"PROCESSING".equals(expected.getStatus())
                || (StringUtils.hasText(expected.getPhase()) && !"RUNNING".equals(expected.getPhase()))) {
            return false;
        }
        LambdaUpdateWrapper<VideoTask> claim = new LambdaUpdateWrapper<VideoTask>()
                .eq(VideoTask::getId, expected.getId())
                .eq(VideoTask::getStatus, "PROCESSING");
        appendExecutionIdentity(claim, expected);
        claim.set(VideoTask::getPhase, "FINALIZING")
                .set(VideoTask::getNextPollAt, null);
        if (!this.update(claim)) {
            return false;
        }
        asyncJobService.enqueue(JOB_TYPE_TASK_FINALIZE,
                finalizeJobKey(expected.getId(), expected.getCurrentAttemptId()),
                finalizePayload(expected, remoteVideoUrl));
        return true;
    }

    private void appendExecutionIdentity(LambdaUpdateWrapper<VideoTask> wrapper, VideoTask expected) {
        if (expected.getCurrentAttemptId() == null) {
            wrapper.isNull(VideoTask::getCurrentAttemptId);
        } else {
            wrapper.eq(VideoTask::getCurrentAttemptId, expected.getCurrentAttemptId());
        }
        if (expected.getProviderTaskId() == null) {
            wrapper.isNull(VideoTask::getProviderTaskId);
        } else {
            wrapper.eq(VideoTask::getProviderTaskId, expected.getProviderTaskId());
        }
        if (expected.getPhase() == null) {
            wrapper.isNull(VideoTask::getPhase);
        } else {
            wrapper.eq(VideoTask::getPhase, expected.getPhase());
        }
        if (expected.getRetryCount() == null) {
            wrapper.isNull(VideoTask::getRetryCount);
        } else {
            wrapper.eq(VideoTask::getRetryCount, expected.getRetryCount());
        }
        if (expected.getProvider() == null) {
            wrapper.isNull(VideoTask::getProvider);
        } else {
            wrapper.eq(VideoTask::getProvider, expected.getProvider());
        }
    }

    private boolean sameExecutionIdentity(VideoTask expected, VideoTask actual) {
        return expected != null
                && Objects.equals(expected.getCurrentAttemptId(), actual.getCurrentAttemptId())
                && Objects.equals(expected.getProviderTaskId(), actual.getProviderTaskId())
                && Objects.equals(expected.getPhase(), actual.getPhase())
                && Objects.equals(expected.getRetryCount(), actual.getRetryCount())
                && Objects.equals(expected.getProvider(), actual.getProvider());
    }

    private String finalizePayload(VideoTask task, String remoteVideoUrl) {
        try {
            return objectMapper.writeValueAsString(new FinalizePayload(
                    task.getId(), task.getCurrentAttemptId(), task.getProviderTaskId(),
                    task.getProvider(), "FINALIZING", task.getRetryCount(), remoteVideoUrl));
        } catch (JsonProcessingException e) {
            // 该方法在 stageFinalize 的短事务中调用：序列化失败必须冒泡，让 FINALIZING CAS 回滚。
            throw new IllegalStateException("无法序列化终态作业", e);
        }
    }

    /**
     * 发布任务终态变化事件。事务提交后由 {@code TaskStreamManager} 监听并经 SSE 推给对应用户，
     * 替代前端轮询。此处仅在成功 / 失败落库后调用，PROCESSING 不发。
     */
    private void publishStatusChanged(VideoTask task) {
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
    }

    private String toUserErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.toLowerCase(Locale.ROOT).contains("copyright")
                ? "涉及版权问题，生成失败"
                : message;
    }

    private record FinalizePayload(Long videoTaskId, Long expectedAttemptId,
                                   String expectedProviderTaskId, String expectedProvider,
                                   String expectedPhase, Integer expectedRetryCount,
                                   String remoteVideoUrl) {
    }
}
