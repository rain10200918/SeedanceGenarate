package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.CanvasArtifactResolver;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** 见 {@link CanvasArtifactResolver}。签名地址在提交那一刻现签，不落库，因此不存在过期问题。 */
@Service
@RequiredArgsConstructor
public class CanvasArtifactResolverImpl implements CanvasArtifactResolver {

    private final VideoTaskService videoTaskService;
    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;

    @Override
    public ResolvedInputs.PortValue toFetchable(CanvasNode producer, ResolvedInputs.PortValue value) {
        if (value == null || !StringUtils.hasText(value.value())) {
            return value;
        }
        // 文本口传的是内容本身，不是地址
        if (value.mediaType() == MediaType.TEXT) {
            return value;
        }
        String raw = value.value();
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return value;
        }

        // 走到这里只剩「生成节点的产物 key」这一种情况
        String taskId = producer == null ? null : producer.getTaskId();
        if (!StringUtils.hasText(taskId)) {
            throw BusinessException.badRequest(name(producer) + "的产物没有关联任务，无法作为下游输入");
        }
        VideoTask task = videoTaskService.getOne(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getBizTaskId, taskId)
                .last("limit 1"), false);
        if (task == null || !"OSS".equals(task.getArtifactStorageType())
                || !StringUtils.hasText(task.getArtifactKey())) {
            throw BusinessException.badRequest(
                    name(producer) + "的产物没有存进对象存储，无法作为下游输入");
        }
        try {
            String url = artifactStorage.createSignedGetUrl(task.getArtifactKey(),
                    Duration.ofSeconds(ossConfig.getSignedUrlTtlSeconds()));
            return new ResolvedInputs.PortValue(value.mediaType(), url);
        } catch (Exception e) {
            throw BusinessException.badRequest(name(producer) + "的产物地址签发失败：" + e.getMessage());
        }
    }

    private String name(CanvasNode producer) {
        String title = producer == null ? null : producer.getTitle();
        return "上游节点「" + (StringUtils.hasText(title) ? title : "未命名") + "」";
    }
}
