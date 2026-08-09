package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.CostRecordService;
import org.example.seedancegenarate.service.VideoDownloadService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class VideoTaskServiceImpl extends ServiceImpl<VideoTaskMapper, VideoTask> implements VideoTaskService {
    private final VideoDownloadService videoDownloadService;
    private final CostRecordService costRecordService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public void updateStatus(VideoTask task, RemoteStatus status) throws Exception {
        if (task == null || status == null || task.getId() == null) {
            return;
        }
        switch (status.getState()) {
            case SUCCESS -> {
                if ("SUCCESS".equals(task.getStatus())) {
                    return; // 幂等：已成功，勿重复下载 / 计费
                }
                // 两类提供方统一转存 OSS：Seedance 地址会过期，ComfyUI /view 又是内网节点地址。
                VideoDownloadService.DownloadedArtifact downloaded = videoDownloadService.download(
                        status.getRemoteVideoUrl(), task.businessTaskId());
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
                LambdaUpdateWrapper<VideoTask> wrapper = new LambdaUpdateWrapper<>();
                wrapper.eq(VideoTask::getId, task.getId())
                        .set(VideoTask::getStatus, "SUCCESS")
                        .set(VideoTask::getVideoUrl, mediaName)
                        .set(VideoTask::getArtifactStorageType, "OSS")
                        .set(VideoTask::getArtifactKey, downloaded.artifact().objectKey())
                        .set(VideoTask::getArtifactContentType, downloaded.artifact().contentType())
                        .set(VideoTask::getArtifactSize, downloaded.artifact().contentLength())
                        .set(VideoTask::getArtifactEtag, downloaded.artifact().etag())
                        .set(VideoTask::getErrorMsg, null);
                this.update(wrapper);
                // 成功计费：仅「成功才计费」的提供方（如 ComfyUI）真正落账，且幂等
                costRecordService.recordOnSuccess(task);
                publishStatusChanged(task);
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
            }
            default -> {
                // PROCESSING：任务初始即为 PROCESSING，无需落库
            }
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
}
