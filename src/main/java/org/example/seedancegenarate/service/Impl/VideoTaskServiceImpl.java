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
import org.example.seedancegenarate.service.VideoDownloadService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 终态作业幂等键。 */
    public static String finalizeJobKey(Long videoTaskId) {
        return "task:" + videoTaskId;
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
                String message = toUserErrorMessage(status.getErrorMsg());
                task.setStatus("FAILED");
                task.setErrorMsg(message);
                LambdaUpdateWrapper<VideoTask> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(VideoTask::getId, task.getId())
                        .set(VideoTask::getStatus, "FAILED")
                        .set(VideoTask::getErrorMsg, message);
                this.update(wrapper);
                publishStatusChanged(task);
                log.warn("任务失败: taskId={}, provider={}, reason={}",
                        task.businessTaskId(), task.getProvider(), message);
            }
            default -> {
                // PROCESSING：任务初始即为 PROCESSING，无需落库
            }
        }
    }

    @Override
    @Transactional
    public void finalizeTask(Long videoTaskId, String remoteVideoUrl) throws Exception {
        VideoTask task = this.getById(videoTaskId);
        if (task == null || !"PROCESSING".equals(task.getStatus())) {
            log.info("终态收尾幂等跳过（已终态或不存在）: videoTaskId={}", videoTaskId);
            return;
        }
        log.info("开始终态收尾（下载 → OSS）: videoTaskId={}, url={}", videoTaskId, remoteVideoUrl);
        // 两类提供方统一转存 OSS：Seedance 地址会过期，ComfyUI /view 又是内网节点地址。
        VideoDownloadService.DownloadedArtifact downloaded = videoDownloadService.download(
                remoteVideoUrl, task.businessTaskId());
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
            return; // 其他 Worker 已落终态，幂等跳过
        }
        // 成功计费：仅「成功才计费」的提供方（如 ComfyUI）真正落账，且幂等
        costRecordService.recordOnSuccess(task);
        publishStatusChanged(task);
        log.info("任务终态落库成功: taskId={}, mediaName={}, size={}",
                task.businessTaskId(), mediaName, downloaded.artifact().contentLength());
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
