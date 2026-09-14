package org.example.seedancegenarate.dto;

import org.example.seedancegenarate.entity.VideoTask;

import java.math.BigDecimal;

/** 对外任务白名单；调用方先执行过期与内容访问策略。 */
public record ApiTaskView(
        String taskId, String status, String model, BigDecimal costAmount,
        String videoUrl, Boolean artifactExpired, String errorMsg,
        String outputType, Integer duration, String ratio,
        String processingState, String processingMessage,
        String moderationStatus, String moderationReasonCode, String moderationMessage
) {
    public static ApiTaskView from(VideoTask task) {
        return new ApiTaskView(task.businessTaskId(), task.getStatus(), task.getModel(), task.getCostAmount(),
                publicFileName(task.getVideoUrl()), task.getArtifactExpired(),
                task.getErrorMsg() == null || task.getErrorMsg().isBlank() ? null
                        : "生成任务出现异常，请联系平台查询处理进度。",
                task.getOutputType(), task.getDuration(), task.getRatio(),
                task.processingState(), task.processingMessage(), task.getModerationStatus(),
                task.getModerationReasonCode(), task.getModerationMessage());
    }

    private static String publicFileName(String stored) {
        if (stored == null) return null;
        String name = stored.startsWith("data/videos/") ? stored.substring("data/videos/".length()) : stored;
        // 只兼容路由文件名，不从远程 URL 或任意对象路径中提取最后一段。
        return name.length() <= 255 && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*") ? name : null;
    }
}
