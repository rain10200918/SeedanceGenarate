package org.example.seedancegenarate.exception;

/**
 * 对外 API 统一错误结构（见 API_SERVICE_DESIGN.md §5）：
 * {@code { "error": { "code": ..., "message": ..., "requestId": ... } }}
 * <p>
 * 字段名是<b>驼峰</b>——项目没有配 property-naming-strategy。文档一度写成 request_id,
 * 由 ApiContractFieldNamesTest 钉住。
 */
public record ApiErrorResponse(ApiError error) {

    public record ApiError(String code, String message, String requestId) {
    }
}
