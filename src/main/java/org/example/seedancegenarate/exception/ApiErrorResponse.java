package org.example.seedancegenarate.exception;

/**
 * 对外 API 统一错误结构（见 API_SERVICE_DESIGN.md §5）：
 * {@code { "error": { "code": ..., "message": ..., "request_id": ... } }}
 */
public record ApiErrorResponse(ApiError error) {

    public record ApiError(String code, String message, String requestId) {
    }
}
