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

    /**
     * 产物已被 OSS 生命周期规则删除。410 而不是 404：资源确实存在过，只是不再可得，
     * 调用方据此知道「不用重试，重新生成才行」。
     */
    public static ApiException artifactExpired(String message) {
        return new ApiException("ARTIFACT_EXPIRED", HttpStatus.GONE, message);
    }

    /** 产物仍保留且任务仍成功，但平台内容治理已禁止交付。 */
    public static ApiException contentBlocked(String message) {
        return new ApiException("CONTENT_BLOCKED", HttpStatus.FORBIDDEN, message);
    }

    /**
     * 在途并发已达上限。与 {@link #rateLimited()} 刻意分成两个码：
     * 前者要「等一条跑完」，后者要「退避重试」，客户的处置完全不同。
     */
    public static ApiException concurrencyLimited(int limit, long current) {
        return new ApiException("CONCURRENCY_LIMIT", HttpStatus.TOO_MANY_REQUESTS,
                "同时进行的任务已达上限（" + current + "/" + limit + "），等一条完成后再提交");
    }

    public static ApiException rateLimited() {
        return new ApiException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
    }

    public static ApiException providerUnavailable(String message) {
        return new ApiException("PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static ApiException insufficientBalance() {
        return new ApiException("INSUFFICIENT_BALANCE", HttpStatus.PAYMENT_REQUIRED, "余额不足，请先充值");
    }

    public static ApiException internal(String message) {
        return new ApiException("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
