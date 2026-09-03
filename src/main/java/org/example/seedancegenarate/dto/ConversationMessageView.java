package org.example.seedancegenarate.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/** 对话里的一个气泡；JSON 列已解析成节点，前端直接用 */
public record ConversationMessageView(
        Long id, Integer seq, String role, String kind, String content,
        JsonNode attachments, String taskId, JsonNode genParams, String genStatus,
        JsonNode output, String errorMsg, LocalDateTime createTime) {
}
