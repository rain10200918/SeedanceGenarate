package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.RemoteStatus;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台任务推进器：定时轮询处于 {@code PROCESSING} 的任务，向各提供方拿最新状态并归一化落库
 * （成功即下载到本地并计费，失败记录错误）。
 * <p>
 * 这是「前端不再轮询、改 SSE 推送」后的<b>服务端状态源</b>——状态一旦经 {@code updateStatus} 落库，
 * 便发出 {@code TaskStatusChangedEvent}，由 {@code TaskStreamManager} 推给对应用户。相比旧的
 * 「客户端 GET 触发轮询」，这里与在线客户端数无关，且能在完成后<b>及时下载</b>远端产物，规避
 * Seedance 云端地址过期 / ComfyUI 历史被清。
 * <p>
 * 只扫最近 {@code video.poll.max-age-hours} 小时内的任务并限批量，避免长期卡住的历史行被无限轮询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskPoller {
    private final VideoTaskService videoTaskService;
    private final VideoEngineRegistry videoEngineRegistry;

    @Value("${video.poll.enabled:true}")
    private boolean enabled;

    @Value("${video.poll.max-age-hours:24}")
    private long maxAgeHours;

    @Value("${video.poll.batch-size:200}")
    private int batchSize;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    @Scheduled(fixedDelayString = "${video.poll.interval-ms:2000}",
            initialDelayString = "${video.poll.initial-delay-ms:5000}")
    public void advanceProcessingTasks() {
        if (!enabled) {
            return;
        }
        List<VideoTask> tasks;
        try {
            tasks = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "PROCESSING")
                    .ge(VideoTask::getCreateTime, LocalDateTime.now().minusHours(maxAgeHours))
                    .orderByAsc(VideoTask::getId)
                    .last("limit " + Math.max(batchSize, 1)));
        } catch (Exception e) {
            log.warn("拉取待推进任务失败: {}", e.getMessage());
            return;
        }
        for (VideoTask task : tasks) {
            try {
                String provider = (task.getProvider() == null || task.getProvider().isBlank())
                        ? defaultProvider : task.getProvider().trim();
                RemoteStatus status = videoEngineRegistry.get(provider).poll(task);
                videoTaskService.updateStatus(task, status);
            } catch (Exception e) {
                // 单个任务轮询失败（网络抖动、节点暂时不可达等）不影响其他任务；
                // 不轻易置为 FAILED（那是提供方明确返回失败才做的），下一轮继续重试
                log.warn("推进任务 {} 失败: {}", task.getTaskId(), e.getMessage());
            }
        }
    }
}
