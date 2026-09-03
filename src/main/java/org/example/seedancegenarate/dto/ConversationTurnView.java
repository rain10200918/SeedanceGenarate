package org.example.seedancegenarate.dto;

import java.util.List;

/** 发送一条消息后返回的整轮：用户原话 /（Agent 整理）/ 生成卡片，外加更新后的对话头 */
public record ConversationTurnView(ConversationView conversation, List<ConversationMessageView> messages) {
}
