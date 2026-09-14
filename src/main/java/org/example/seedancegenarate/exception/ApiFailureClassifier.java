package org.example.seedancegenarate.exception;

import org.example.seedancegenarate.service.Impl.WalletServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.beans.TypeMismatchException;

/** API响应和调用日志共享分类；异常消息不是可信的公开文案。 */
public final class ApiFailureClassifier {
    private ApiFailureClassifier() { }

    public static ApiException classify(Exception failure) {
        if (failure instanceof ApiException api) {
            if (api.getCode() == null || !api.getCode().matches("[A-Z][A-Z0-9_]{0,63}")) {
                return statusError(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new ApiException(api.getCode(), api.getHttpStatus(), publicMessage(api));
        }
        if (failure instanceof ConcurrencyLimitExceededException limited) {
            return ApiException.concurrencyLimited(limited.getLimit(), limited.getCurrent());
        }
        if (failure instanceof WalletServiceImpl.InsufficientBalanceException) {
            return ApiException.insufficientBalance();
        }
        if (failure instanceof BusinessException business) {
            HttpStatus status = HttpStatus.resolve(business.getCode());
            if (status != null && status.is4xxClientError()) return statusError(status);
            return statusError(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (failure instanceof HandlerMethodValidationException validation) {
            return statusError(validation.isForReturnValue() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST);
        }
        if (failure instanceof HttpMessageNotReadableException || failure instanceof BindException
                || failure instanceof ServletRequestBindingException || failure instanceof TypeMismatchException) {
            return statusError(HttpStatus.BAD_REQUEST);
        }
        if (failure instanceof ErrorResponse error) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            return statusError(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status);
        }
        // Only these two legacy RuntimeException literals are known business signals.
        if (failure.getClass() == RuntimeException.class) {
            if ("余额不足，请先充值".equals(failure.getMessage())) return ApiException.insufficientBalance();
            if ("该模型未开放".equals(failure.getMessage())) return ApiException.modelNotOpen();
        }
        if (failure instanceof IllegalArgumentException) return statusError(HttpStatus.BAD_REQUEST);
        return statusError(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ApiException statusError(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> ApiException.validation("请求参数不合法");
            case UNAUTHORIZED -> new ApiException("UNAUTHORIZED", status, "请提供有效凭证");
            case FORBIDDEN -> new ApiException("FORBIDDEN", status, "无权访问此资源");
            case NOT_FOUND -> new ApiException("NOT_FOUND", status, "资源不存在");
            case METHOD_NOT_ALLOWED -> new ApiException("METHOD_NOT_ALLOWED", status, "不支持的请求方式");
            case NOT_ACCEPTABLE -> new ApiException("NOT_ACCEPTABLE", status, "不支持的响应媒体类型");
            case CONFLICT -> new ApiException("CONFLICT", status, "请求与当前状态冲突");
            case PAYLOAD_TOO_LARGE -> new ApiException("PAYLOAD_TOO_LARGE", status, "请求体超过大小限制");
            case UNSUPPORTED_MEDIA_TYPE -> new ApiException("UNSUPPORTED_MEDIA_TYPE", status, "不支持的请求媒体类型");
            case TOO_MANY_REQUESTS -> ApiException.rateLimited();
            case SERVICE_UNAVAILABLE -> ApiException.providerUnavailable("服务暂不可用，请稍后重试");
            default -> ApiException.internal("系统繁忙，请稍后重试");
        };
    }

    private static String publicMessage(ApiException api) {
        if (api.getHttpStatus().is5xxServerError()) {
            return api.getHttpStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                    ? "系统繁忙，请稍后重试" : "服务暂不可用，请稍后重试";
        }
        return switch (api.getCode()) {
            case "VALIDATION_ERROR" -> validationMessage(api.getMessage());
            case "INVALID_API_KEY" -> "API Key 无效";
            case "API_KEY_DISABLED" -> "API Key 已被禁用";
            case "API_KEY_EXPIRED" -> "API Key 已过期";
            case "MODEL_NOT_FOUND" -> "模型不存在";
            case "MODEL_NOT_OPEN" -> "该模型未开放";
            case "INSUFFICIENT_BALANCE" -> "余额不足，请先充值";
            case "API_KEY_SPENDING_LIMIT_EXCEEDED" -> "该密钥本月消费额度不足，请联系管理员调整或等待下月";
            case "TASK_NOT_FOUND" -> "任务不存在";
            case "ARTIFACT_EXPIRED" -> "产物已过期，请重新生成";
            case "CONTENT_BLOCKED" -> "产物已被屏蔽，暂不可访问";
            case "CONCURRENCY_LIMIT" -> "同时进行的任务已达上限，请等待任务完成";
            case "RATE_LIMITED" -> "请求过于频繁，请稍后再试";
            case "IDEMPOTENCY_KEY_REUSED" -> "相同 Idempotency-Key 不能用于不同请求参数";
            case "REQUEST_IN_PROGRESS" -> "请求正在处理中，请使用相同 Idempotency-Key 重试";
            default -> "请求未能完成";
        };
    }

    private static String validationMessage(String message) {
        // Anchored templates contain only fixed wording and bounded numeric values, never submitted URLs.
        if (message != null && (message.matches("参考(?:图|视频|音频)(?:大小超过限制 \\(最大 30MB\\)|地址格式不合法|类型不匹配|内容为空|下载失败|地址端口无效|地址不能包含用户名密码|地址不能为空且不能超过 4096 个字符|重定向次数超过限制|重定向缺少目标地址|重定向地址格式不合法)")
                || message.matches("禁止使用内网或本地参考(?:图|视频|音频)地址")
                || message.matches("参考媒体总(?:大小超过限制 \\(最大 100MiB\\)|数不能超过 16)")
                || message.matches("(?:prompt|model|ratio|duration|Idempotency-Key)(?: 不能为空| 不能为空且不能超过 (?:64|5000) 个字符| 不能超过 (?:32|5000) 个字符| 必须在 1 到 600 之间)"))) {
            return message;
        }
        return "请求参数不合法";
    }
}
