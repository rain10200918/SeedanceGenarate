package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.TaskStatusTransitioner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Locale;

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

    @Override
    public boolean markFailed(Long videoTaskId, String message) {
        return markFailedInternal(videoTaskId, message, "任务失败");
    }

    @Override
    public boolean markTimedOut(Long videoTaskId, String message) {
        return markFailedInternal(videoTaskId, message, "任务超时终止");
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

    private boolean markFailedInternal(Long videoTaskId, String message, String logLabel) {
        if (videoTaskId == null) {
            return false;
        }
        VideoTask task = videoTaskMapper.selectById(videoTaskId);
        if (task == null || !"PROCESSING".equals(task.getStatus())) {
            return false; // 幂等：不存在或已终态（不覆盖成功结果）
        }
        String userMsg = toUserErrorMessage(message);
        int rows = videoTaskMapper.update(null, Wrappers.<VideoTask>lambdaUpdate()
                .eq(VideoTask::getId, videoTaskId)
                .eq(VideoTask::getStatus, "PROCESSING")
                .set(VideoTask::getStatus, "FAILED")
                .set(VideoTask::getErrorMsg, userMsg));
        if (rows == 0) {
            return false; // 并发下其他写入者已落终态
        }
        task.setStatus("FAILED");
        task.setErrorMsg(userMsg);
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

    private String toUserErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.toLowerCase(Locale.ROOT).contains("copyright")
                ? "涉及版权问题，生成失败"
                : message;
    }
}
