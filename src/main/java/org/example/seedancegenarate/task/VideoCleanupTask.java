package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地视频过期清理：前端提示「视频 2 天后失效」，这里真正落地——
 * 每日清理 data/videos/ 下超过 48h 的文件，并把对应任务 videoUrl 置空（播放将提示已过期）。
 * 之前只有提示没有清理，磁盘只进不出（ComfyUI 磁盘满事故的同类隐患）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoCleanupTask {

    private static final String VIDEO_PATH = "data/videos/";
    private static final long MAX_AGE_MS = 48L * 3600 * 1000;

    private final VideoTaskService videoTaskService;

    @Scheduled(cron = "${video.cleanup-cron:0 30 3 * * *}")
    public void cleanupExpiredVideos() {
        Path dir = Paths.get(VIDEO_PATH);
        if (!Files.exists(dir)) {
            return;
        }
        List<String> removed = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isExpired)
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            removed.add(p.getFileName().toString());
                        } catch (IOException e) {
                            log.warn("清理视频文件失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("扫描视频目录失败", e);
            return;
        }
        if (removed.isEmpty()) {
            return;
        }
        // 对应任务的 videoUrl 置空：前端详情/列表将不再展示可播放地址
        for (String name : removed) {
            boolean updated = videoTaskService.update(Wrappers.<VideoTask>lambdaUpdate()
                    .eq(VideoTask::getVideoUrl, VIDEO_PATH + name)
                    .set(VideoTask::getVideoUrl, null));
            log.info("已清理过期视频 {}，任务回写 {}", name, updated);
        }
    }

    private boolean isExpired(Path p) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > MAX_AGE_MS;
        } catch (IOException e) {
            return false;
        }
    }
}
