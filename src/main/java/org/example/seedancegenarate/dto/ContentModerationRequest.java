package org.example.seedancegenarate.dto;

/** 管理员内容审核写请求；expectedVersion 用于阻止两个审核员互相覆盖。 */
public record ContentModerationRequest(
        Integer expectedVersion,
        String reasonCode,
        String userMessage,
        String internalNote
) {
}
