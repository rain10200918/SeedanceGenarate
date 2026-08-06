package org.example.seedancegenarate.exception;

import org.springframework.http.HttpStatus;

/**
 * 对外 API 的错误契约：统一 {@code {error:{code,message,request_id}}} 输出（见 API_SERVICE_DESIGN.md §5）。
 * 与 UI 侧的 RuntimeException → Result.fail 分离，避免 API 失败被全局兜底成 500。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;

    public ApiException(String code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    // —— 常用工厂，语义即文档 ——

    public static ApiException invalidApiKey() {
        return new ApiException("INVALID_API_KEY", HttpStatus.UNAUTHORIZED, "API Key 无效");
    }

    public static ApiException apiKeyDisabled() {
        return new ApiException("API_KEY_DISABLED", HttpStatus.FORBIDDEN, "API Key 已被禁用");
    }

    public static ApiException apiKeyExpired() {
        return new ApiException("API_KEY_EXPIRED", HttpStatus.FORBIDDEN, "API Key 已过期");
    }

    public static ApiException validation(String message) {
        return new ApiException("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException modelNotFound(String model) {
        return new ApiException("MODEL_NOT_FOUND", HttpStatus.BAD_REQUEST, "模型不存在: " + model);
    }

    public static ApiException modelNotOpen() {
        return new ApiException("MODEL_NOT_OPEN", HttpStatus.FORBIDDEN, "该模型未开放");
    }

    public static ApiException taskNotFound() {
        return new ApiException("TASK_NOT_FOUND", HttpStatus.NOT_FOUND, "任务不存在");
    }

    public static ApiException rateLimited() {
        return new ApiException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }

    public static ApiException providerUnavailable(String message) {
        return new ApiException("PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException internal(String message) {
        return new ApiException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
