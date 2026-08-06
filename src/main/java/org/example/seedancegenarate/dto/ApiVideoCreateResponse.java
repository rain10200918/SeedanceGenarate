package org.example.seedancegenarate.dto;

/**
 * 提交成功响应（202，异步）：轮询 /webhook 用 taskId 追踪。
 */
public record ApiVideoCreateResponse(String taskId, String status, String requestId) {
}
