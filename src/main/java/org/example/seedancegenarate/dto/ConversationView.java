package org.example.seedancegenarate.dto;

import org.example.seedancegenarate.entity.Conversation;

import java.time.LocalDateTime;

/** 左栏一条对话 */
public record ConversationView(
        Long id, String title, LocalDateTime lastMessageAt, Integer messageCount,
        boolean archived, LocalDateTime createTime) {

    public static ConversationView of(Conversation c) {
        return new ConversationView(c.getId(), c.getTitle(), c.getLastMessageAt(), c.getMessageCount(),
                Boolean.TRUE.equals(c.getArchived()), c.getCreateTime());
    }
}
