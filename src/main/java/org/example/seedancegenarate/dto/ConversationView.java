package org.example.seedancegenarate.dto;

import org.example.seedancegenarate.entity.Conversation;

import java.time.LocalDateTime;

/**
 * 左栏一条对话。
 * <p>
 * cover 是最近一次成功结果的缩略图：图片直接用结果图；视频没有封面帧，用第一张参考图顶上（和素材墙一个做法）；
 * 音频没有图。coverUrl 可能为空而 coverType 有值——前端按类型画占位图标。
 */
public record ConversationView(
        Long id, String title, LocalDateTime lastMessageAt, Integer messageCount,
        boolean archived, LocalDateTime createTime, String coverUrl, String coverType) {

    public record Cover(String url, String type) {
    }

    public static ConversationView of(Conversation c) {
        return of(c, null);
    }

    public static ConversationView of(Conversation c, Cover cover) {
        return new ConversationView(c.getId(), c.getTitle(), c.getLastMessageAt(), c.getMessageCount(),
                Boolean.TRUE.equals(c.getArchived()), c.getCreateTime(),
                cover == null ? null : cover.url(), cover == null ? null : cover.type());
    }
}
