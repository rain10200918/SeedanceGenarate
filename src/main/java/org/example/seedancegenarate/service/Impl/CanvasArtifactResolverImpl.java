package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.canvas.MediaType;
import org.example.seedancegenarate.canvas.ResolvedInputs;
import org.example.seedancegenarate.config.OssConfig;
import org.example.seedancegenarate.entity.CanvasNode;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.ArtifactExpiryPolicy;
import org.example.seedancegenarate.service.ArtifactStorage;
import org.example.seedancegenarate.service.CanvasArtifactResolver;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 见 {@link CanvasArtifactResolver}。签名地址在提交那一刻现签、不落库，所以<b>签名</b>不会过期。
 * 但<b>对象本身</b>会被 OSS 生命周期规则删除，那是另一回事，由 {@link ArtifactExpiryPolicy} 挡在提交之前。
 */
@Service
@RequiredArgsConstructor
public class CanvasArtifactResolverImpl implements CanvasArtifactResolver {

    private final VideoTaskService videoTaskService;
    private final ArtifactStorage artifactStorage;
    private final OssConfig ossConfig;
    private final ArtifactExpiryPolicy artifactExpiryPolicy;

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
        // 产物过期必须在这里挡住，不能等引擎去下载才发现。
        // 放行的话顺序是：签地址（不校验对象存在）→ 提交 → **钱先冻结** → 引擎 downloadBytes()
        // 拿到 404 → markFailed → 退款。钱最终不会错，但用户看到的是一个莫名其妙的失败，
        // 而且 GPU 排队位白占。这是 D-014 那类事故的翻版（当时 4 条任务各冻 2.40）。
        if (artifactExpiryPolicy.isExpired(task)) {
            throw BusinessException.badRequest(
                    name(producer) + "的产物已过期（仅保留 "
                            + artifactExpiryPolicy.getRetentionDays() + " 天），请重新生成后再运行");
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
