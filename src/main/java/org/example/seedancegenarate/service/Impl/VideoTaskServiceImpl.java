package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;
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

import java.math.BigDecimal;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoTaskService {
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
    /** 显式事务：不用 @Transactional 抽方法——同类内自调用会绕过代理，事务会静默消失 */
    private final TransactionTemplate transactionTemplate;

    /** 终态作业幂等键。 */
    public static String finalizeJobKey(Long videoTaskId) {
        return "task:" + videoTaskId;
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
    public VideoTask getByProviderTaskId(String providerTaskId) {
        if (providerTaskId == null || providerTaskId.isBlank()) {
            return null;
        }
        return this.getOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<VideoTask>lambdaQuery()
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
                // 轻量推进：下载/OSS/计费交给 TASK_FINALIZE 作业由多实例 Worker 并行完成，
                // poller 不被大文件下载拖住。作业幂等键防重复入队。
                asyncJobService.enqueue(JOB_TYPE_TASK_FINALIZE, finalizeJobKey(task.getId()),
                        "{\"videoTaskId\":" + task.getId()
                                + ",\"remoteVideoUrl\":\"" + safeJson(status.getRemoteVideoUrl()) + "\"}");
                log.info("任务完成，已入队终态作业: taskId={}, provider={}, url={}",
                        task.businessTaskId(), task.getProvider(), status.getRemoteVideoUrl());
            }
            case FAILED -> {
                // 统一走终态唯一入口：CAS PROCESSING→FAILED + SSE 通知（幂等，已终态不覆盖）
                taskStatusTransitioner.markFailed(task.getId(), status.getErrorMsg());
            }
            case LOST -> {
                // 作业在远端消失（节点重启清空了内存队列）：不是用户的失败，立刻重投而不是等超龄
                if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                    return; // 已终态，忽略迟到的丢失判定
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
     * 消费是串行的所以现在只占 1 条连接（池上限 50）—— 它是随实例数与并发度线性恶化的隐患，
     * 不是当前故障。
     * <p>
     * 用 {@link TransactionTemplate} 而不是把后半段抽成 {@code @Transactional} 方法：
     * 同类内自调用会绕过代理，<b>事务会静默消失</b>——那是这条路径上最不能出的错。
     */
    @Override
    public void finalizeTask(Long videoTaskId, String remoteVideoUrl) throws Exception {
        VideoTask task = this.getById(videoTaskId);
        if (task == null || !"PROCESSING".equals(task.getStatus())) {
            log.info("终态收尾幂等跳过（已终态或不存在）: videoTaskId={}", videoTaskId);
            return;
        }
        log.info("开始终态收尾（下载 → OSS）: videoTaskId={}, url={}", videoTaskId, remoteVideoUrl);
        // 两类提供方统一转存 OSS：Seedance 地址会过期，ComfyUI /view 又是内网节点地址。
        // 并发安全：objectKey 由 bizTaskId 决定，两个 Worker 同时下载会覆盖同一个对象；
        // 谁能落库由下面的 CAS 决定，只有一个赢。
        VideoDownloadService.DownloadedArtifact downloaded = videoDownloadService.download(
                remoteVideoUrl, task.businessTaskId());
        Boolean committed = transactionTemplate.execute(status -> commitTerminal(task, downloaded));
        if (!Boolean.TRUE.equals(committed)) {
            return; // 其他 Worker 已落终态，幂等跳过
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
                .eq(VideoTask::getStatus, "PROCESSING")
                .set(VideoTask::getStatus, "SUCCESS")
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
        walletService.settle(task.getUserId(), settleAmount, task.getId());
        // 留在事务内：CanvasEventListener / PipelineEventListener 是裸 @EventListener（同步立即执行），
        // 它们的节点回填写入现在就在这个事务里，挪出去会改变画布回填与终态的原子性。
        publishStatusChanged(task);
        return true;
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

    /** 转义 JSON 字符串值（URL 中可能含引号/反斜杠）。 */
    private String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
