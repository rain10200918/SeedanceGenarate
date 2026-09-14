package org.example.seedancegenarate.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 对外 API 的异常映射：ApiException → 统一 {@link ApiErrorResponse} + 正确 HTTP 状态。
 * <p>
 * 必须带 {@code @Order(HIGHEST_PRECEDENCE)}：Spring 跨 advice 取「第一个有匹配的 advice」而非
 * 最具体的——GlobalExceptionHandler 有 {@code @ExceptionHandler(Exception.class)} 兜底，
 * 不加 Order 会把 ApiException 吞成 Result.fail 500（2026-08-06 实测）。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiException exception, HttpServletRequest request,
                                                  HttpServletResponse response) {
        if (response.isCommitted()) return null;
        return handle(exception, request);
    }

    /** 保留直接调用及standalone MVC场景使用的原签名。 */
    public ResponseEntity<ApiErrorResponse> handle(ApiException exception, HttpServletRequest request) {
        exception = ApiFailureClassifier.classify(exception);
        log.warn("API 请求失败: code={} status={} requestId={}",
                exception.getCode(), exception.getHttpStatus().value(),
                requestId(request));
        HttpStatus status = exception.getHttpStatus();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header("Retry-After", "30");
        }
        return builder.body(new ApiErrorResponse(
                new ApiErrorResponse.ApiError(exception.getCode(), exception.getMessage(), requestId(request))));
    }

    /** 请求追踪号：优先取客户端幂等键，否则现场生成 */
    public static String requestId(HttpServletRequest request) {
        String attribute = ApiExceptionHandler.class.getName() + ".requestId";
        Object existing = request.getAttribute(attribute);
        if (existing instanceof String id) return id;
        String idempotency = request.getHeader("Idempotency-Key");
        String id;
        if (idempotency != null && idempotency.trim().length() <= 64 && !idempotency.isBlank()
                && idempotency.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)) {
            id = idempotency.trim();
        } else {
            id = "req_" + java.util.UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(attribute, id);
        return id;
    }
}
