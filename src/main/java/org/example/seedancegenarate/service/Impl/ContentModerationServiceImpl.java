package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ContentModerationRequest;
import org.example.seedancegenarate.entity.ContentModerationAction;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.ContentModerationActionMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.ContentModerationPolicy;
import org.example.seedancegenarate.service.ContentModerationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContentModerationServiceImpl implements ContentModerationService {

    private static final Set<String> REASONS = Set.of(
            "SEXUAL_CONTENT", "MINOR_SAFETY", "VIOLENCE", "ILLEGAL_CONTENT", "IP_COMPLAINT", "OTHER");
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "SEXUAL_CONTENT", "此内容因涉嫌包含色情或裸露内容，暂不可查看、下载或继续创作。",
            "MINOR_SAFETY", "此内容因涉嫌涉及未成年人安全，暂不可查看、下载或继续创作。",
            "VIOLENCE", "此内容因涉嫌包含暴力或血腥内容，暂不可查看、下载或继续创作。",
            "ILLEGAL_CONTENT", "此内容因涉嫌违反法律法规或平台规则，暂不可查看、下载或继续创作。",
            "IP_COMPLAINT", "此内容因收到知识产权相关投诉，暂不可查看、下载或继续创作。",
            "OTHER", ContentModerationPolicy.DEFAULT_BLOCKED_MESSAGE);

    private final VideoTaskMapper videoTaskMapper;
    private final ContentModerationActionMapper actionMapper;

    @Override
    public Page<VideoTask> page(long current, long size, String moderationStatus, String keyword) {
        Page<VideoTask> page = new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), 100));
        LambdaQueryWrapper<VideoTask> query = new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getStatus, "SUCCESS")
                .isNotNull(VideoTask::getVideoUrl)
                .ne(VideoTask::getVideoUrl, "")
                .orderByDesc(VideoTask::getCreateTime)
                .orderByDesc(VideoTask::getId);
        String normalizedStatus = normalizeStatus(moderationStatus);
        if (!"ALL".equals(normalizedStatus)) {
            query.eq(VideoTask::getModerationStatus, normalizedStatus);
        }
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            if (value.length() > 100) throw BusinessException.badRequest("搜索关键词不能超过 100 个字符");
            query.and(q -> q.like(VideoTask::getBizTaskId, value)
                    .or().like(VideoTask::getTaskId, value)
                    .or().like(VideoTask::getPrompt, value)
                    .or().like(VideoTask::getModel, value));
        }
        return videoTaskMapper.selectPage(page, query);
    }

    @Override
    @Transactional
    public VideoTask block(Long taskId, ContentModerationRequest request, Long operatorId) {
        VideoTask task = requireReviewable(taskId);
        if (ContentModerationPolicy.BLOCKED.equals(task.getModerationStatus())) {
            return task; // 重放幂等：不重复插审计
        }
        int expectedVersion = expectedVersion(request);
        String reason = normalizeReason(request == null ? null : request.reasonCode());
        String message = limited(request == null ? null : request.userMessage(), 255, "用户说明");
        if (!StringUtils.hasText(message)) message = DEFAULT_MESSAGES.get(reason);
        String note = limited(request == null ? null : request.internalNote(), 4000, "内部备注");
        LocalDateTime now = LocalDateTime.now();

        int changed = videoTaskMapper.blockContent(task.getId(), expectedVersion, reason,
                message, operatorId, now);
        if (changed != 1) return resolveConcurrent(taskId, ContentModerationPolicy.BLOCKED);

        actionMapper.insert(action(task, "BLOCK", ContentModerationPolicy.BLOCKED,
                reason, message, note, operatorId, now));
        task.setModerationStatus(ContentModerationPolicy.BLOCKED);
        task.setModerationReasonCode(reason);
        task.setModerationMessage(message);
        task.setModeratedBy(operatorId);
        task.setModeratedAt(now);
        task.setModerationVersion(expectedVersion + 1);
        return task;
    }

    @Override
    @Transactional
    public VideoTask restore(Long taskId, ContentModerationRequest request, Long operatorId) {
        VideoTask task = requireTask(taskId);
        if (!ContentModerationPolicy.BLOCKED.equals(task.getModerationStatus())) {
            return task; // 重放幂等：已经恢复时不重复插审计
        }
        int expectedVersion = expectedVersion(request);
        String note = limited(request == null ? null : request.internalNote(), 4000, "内部备注");
        LocalDateTime now = LocalDateTime.now();
        int changed = videoTaskMapper.restoreContent(task.getId(), expectedVersion, operatorId, now);
        if (changed != 1) return resolveConcurrent(taskId, ContentModerationPolicy.VISIBLE);

        actionMapper.insert(action(task, "RESTORE", ContentModerationPolicy.VISIBLE,
                task.getModerationReasonCode(), task.getModerationMessage(), note, operatorId, now));
        task.setModerationStatus(ContentModerationPolicy.VISIBLE);
        task.setModerationReasonCode(null);
        task.setModerationMessage(null);
        task.setModeratedBy(operatorId);
        task.setModeratedAt(now);
        task.setModerationVersion(expectedVersion + 1);
        return task;
    }

    @Override
    public List<ContentModerationAction> history(Long taskId) {
        requireTask(taskId);
        return actionMapper.selectList(new LambdaQueryWrapper<ContentModerationAction>()
                .eq(ContentModerationAction::getVideoTaskId, taskId)
                .orderByDesc(ContentModerationAction::getId));
    }

    private VideoTask requireReviewable(Long taskId) {
        VideoTask task = requireTask(taskId);
        if (!"SUCCESS".equals(task.getStatus()) || !StringUtils.hasText(task.getVideoUrl())) {
            throw BusinessException.badRequest("只有已经生成成功且存在产物的任务可以屏蔽");
        }
        return task;
    }

    private VideoTask requireTask(Long taskId) {
        if (taskId == null) throw BusinessException.badRequest("任务 ID 不能为空");
        VideoTask task = videoTaskMapper.selectById(taskId);
        if (task == null) throw BusinessException.notFound("任务不存在");
        if (!StringUtils.hasText(task.getModerationStatus())) {
            task.setModerationStatus(ContentModerationPolicy.VISIBLE);
        }
        if (task.getModerationVersion() == null) task.setModerationVersion(0);
        return task;
    }

    private VideoTask resolveConcurrent(Long taskId, String desiredStatus) {
        VideoTask current = requireTask(taskId);
        if (desiredStatus.equals(current.getModerationStatus())) return current;
        throw BusinessException.conflict("内容状态已被其他管理员修改，请刷新后重试");
    }

    private int expectedVersion(ContentModerationRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw BusinessException.badRequest("缺少有效的 expectedVersion");
        }
        return request.expectedVersion();
    }

    private String normalizeReason(String value) {
        if (!StringUtils.hasText(value)) throw BusinessException.badRequest("请选择屏蔽原因");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(normalized)) throw BusinessException.badRequest("不支持的屏蔽原因");
        return normalized;
    }

    private String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) return "ALL";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", ContentModerationPolicy.VISIBLE, ContentModerationPolicy.BLOCKED).contains(normalized)) {
            throw BusinessException.badRequest("不支持的审核状态");
        }
        return normalized;
    }

    private String limited(String value, int max, String label) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) throw BusinessException.badRequest(label + "不能超过 " + max + " 个字符");
        return trimmed;
    }

    private ContentModerationAction action(VideoTask task, String action, String toStatus,
                                           String reason, String message, String note,
                                           Long operatorId, LocalDateTime now) {
        ContentModerationAction row = new ContentModerationAction();
        row.setVideoTaskId(task.getId());
        row.setAction(action);
        row.setFromStatus(task.getModerationStatus());
        row.setToStatus(toStatus);
        row.setReasonCode(reason);
        row.setUserMessage(message);
        row.setInternalNote(note);
        row.setOperatorId(operatorId);
        row.setCreateTime(now);
        return row;
    }
}
