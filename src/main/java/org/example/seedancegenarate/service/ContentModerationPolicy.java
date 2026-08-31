package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.seedancegenarate.entity.VideoTask;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生成产物能否交付的唯一判定入口。
 * <p>屏蔽不改变 SUCCESS、不清产物；普通出口在签地址前调用本策略并对返回 DTO 做脱敏。</p>
 */
@Component
public class ContentModerationPolicy {

    public static final String VISIBLE = "VISIBLE";
    public static final String BLOCKED = "BLOCKED";
    public static final String DEFAULT_BLOCKED_MESSAGE = "此内容因涉嫌违反平台规则，暂不可查看、下载或继续创作。";

    public boolean isBlocked(VideoTask task) {
        return task != null && BLOCKED.equalsIgnoreCase(task.getModerationStatus());
    }

    public String blockedMessage(VideoTask task) {
        if (task != null && task.getModerationMessage() != null
                && !task.getModerationMessage().isBlank()) {
            return task.getModerationMessage();
        }
        return DEFAULT_BLOCKED_MESSAGE;
    }

    /** 普通用户的 JSON 响应不下发产物路由标识；任务、费用与审核说明继续保留。 */
    public VideoTask redact(VideoTask task) {
        if (task == null) return null;
        if (isBlocked(task)) {
            task.setVideoUrl(null);
        }
        // 操作员与并发版本只服务管理端，不属于用户侧内容契约。
        task.setModeratedBy(null);
        task.setModerationVersion(null);
        return task;
    }

    public Page<VideoTask> redactAll(Page<VideoTask> page) {
        if (page != null) redactAll(page.getRecords());
        return page;
    }

    public List<VideoTask> redactAll(List<VideoTask> tasks) {
        if (tasks != null) tasks.forEach(this::redact);
        return tasks;
    }
}
