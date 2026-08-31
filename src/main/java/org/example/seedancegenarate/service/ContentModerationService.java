package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.dto.ContentModerationRequest;
import org.example.seedancegenarate.entity.ContentModerationAction;
import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

public interface ContentModerationService {
    Page<VideoTask> page(long current, long size, String moderationStatus, String keyword);

    VideoTask block(Long taskId, ContentModerationRequest request, Long operatorId);

    VideoTask restore(Long taskId, ContentModerationRequest request, Long operatorId);

    List<ContentModerationAction> history(Long taskId);
}
