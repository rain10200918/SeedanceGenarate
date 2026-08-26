package org.example.seedancegenarate.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * UI 侧统一异常兜底（Result 契约）。对外 API 的 ApiException 由
 * {@code exception.ApiExceptionHandler} 优先处理（@Order 最高）。
 * 细化异常分类，区分预期内业务提示与未知系统崩溃，保持错误日志干净。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 1. 业务异常（预期内的业务拦截，如余额不足、权限不足等）：记录 WARN，不打印无用堆栈 */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务拦截: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 2. 合法性断言/参数非法异常（IllegalArgumentException / IllegalStateException）：记录 WARN */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result<?> handleIllegalException(RuntimeException e) {
        log.warn("参数或状态校验失败: {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    /** 3. Spring MVC @RequestBody 参数校验异常（@Valid / @Validated） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("请求体参数校验失败: {}", message);
        return Result.fail(400, message.isBlank() ? "请求参数不合法" : message);
    }

    /** 4. 表单参数绑定校验异常 */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("表单参数校验失败: {}", message);
        return Result.fail(400, message.isBlank() ? "表单参数不合法" : message);
    }

    /** 5. 缺少必要请求参数异常 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return Result.fail(400, "缺少必要参数: " + e.getParameterName());
    }

    /** 6. 上传文件大小超出限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("用户上传文件超出限制大小: {}", e.getMessage());
        return Result.fail(400, "上传文件体积过大，请压缩或裁剪后重试");
    }

    /** 7. 请求方法不支持（如 GET 请求了 POST 接口） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {}", e.getMethod());
        return Result.fail(405, "不支持的请求方式: " + e.getMethod());
    }

    /** 8. 客户端主动断开连接（如用户关闭网页、刷新页面、SSE/下载中断）：正常网络事件，静默处理避免污染日志与二次异常 */
    @ExceptionHandler({
            org.apache.catalina.connector.ClientAbortException.class,
            org.springframework.web.context.request.async.AsyncRequestNotUsableException.class
    })
    public void handleClientAbort(Exception e) {
        log.debug("客户端主动断开连接 (SSE/下载): {}", e.getMessage());
    }

    /** 9. 网络 IO 异常处理：区分正常 Broken pipe 与真实 IO 故障 */
    @ExceptionHandler(java.io.IOException.class)
    public void handleIOException(java.io.IOException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("broken pipe") || msg.contains("connection reset")) {
            log.debug("网络连接已断开 (Broken pipe / Connection reset): {}", e.getMessage());
            return;
        }
        log.error("系统 IO 异常: {}", e.getMessage(), e);
    }

    /** 10. 未登录兜底与常规运行时异常 */
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException exception) {
        if ("请先登录".equals(exception.getMessage())) {
            return Result.unauthorized(exception.getMessage());
        }
        log.error("业务处理未知异常: {}", exception.getMessage(), exception);
        return Result.fail(exception.getMessage());
    }

    /** 11. 系统级未知异常（500 兜底，带堆栈记日志便于排障） */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception exception) {
        log.error("系统未知错误: {}", exception.getMessage(), exception);
        return Result.fail("系统繁忙，请稍后重试");
    }
}
