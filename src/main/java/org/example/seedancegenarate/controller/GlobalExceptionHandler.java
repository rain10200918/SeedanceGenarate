package org.example.seedancegenarate.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.http.ResponseEntity;
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

    /** 2. 参数非法：记录 WARN */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数或状态校验失败: {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    /**
     * 2.5 状态非法。绝大多数是业务断言（如「钱包冻结余额不足」），照旧返回 Result；
     * 但 SSE / 异步请求的生命周期事件必须单独摘出来。
     * <p>
     * 2026-08-26 线上实测：客户端断开重连导致 {@code Cannot start async}，落到这里后
     * 处理器往一个 Content-Type 已经是 {@code text/event-stream} 的响应里写 Result ——
     * <b>没有对应的转换器，异常处理器自己再炸一次</b>，一次客户端抖动打出 4 条日志、3 份堆栈。
     * 这类事件和 {@code ClientAbortException} 同性质：是客户端行为，不是服务端错误。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Cannot start async") || message.contains("AsyncContext")) {
            log.debug("异步请求已不可用（SSE 断开 / 重连）: {}", message);
            // 不写 body：响应可能已被钉成 text/event-stream，写什么都会二次抛异常
            return ResponseEntity.noContent().build();
        }
        log.warn("参数或状态校验失败: {}", message);
        return ResponseEntity.ok(Result.fail(400, message));
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

    /**
     * 8.5 异步请求到期：SSE 的 emitter 有 30 分钟上限（{@code TaskStreamManager.EMITTER_TIMEOUT_MS}），
     * 到期即抛，客户端随后重连。这是<b>设计内的正常事件</b>，不是服务端错误。
     * <p>
     * 2026-08-26 线上实测：它是 {@code RuntimeException} 的子类且 {@code getMessage()} 返回
     * {@code null}，所以既躲过了上面按 message 判定的异步分支，也躲过了 ClientAbort 那一组，
     * 一路掉进 #10 打出 ~20 行 ERROR 堆栈，接着 #10 往一个 Content-Type 已是
     * {@code text/event-stream} 的响应里写 {@code Result} —— <b>处理器自己再炸一次</b>
     * （{@code No converter for [Result] with preset Content-Type 'text/event-stream'}）。
     * <p>
     * 每条 SSE 连接每 30 分钟贡献一次，是当前 ERROR 级噪音的最大来源。
     * 返回 void = 不写 body，交给容器按已提交的响应收尾（Spring 自己的
     * {@code DefaultHandlerExceptionResolver} 本来就会正确处理成 503）。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeout(
            org.springframework.web.context.request.async.AsyncRequestTimeoutException e) {
        log.debug("异步请求到期（SSE emitter 超时，客户端会重连）");
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

    /**
     * 9.5 路径不存在：这是 404，不是系统错误。
     * <p>
     * 2026-08-26 线上实测：机器正被批量扫描 VPN/邮件网关漏洞
     * （{@code global-protect/login.esp}、{@code remote/login}、{@code mifs/login.jsp}、{@code owa} …），
     * 每秒一发。改动前每一发都落到 #11，打一份 ~50 行 ERROR 堆栈并返回 200 ——
     * <b>日志被淹（真错误埋在里面找不到），而且对扫描器来说每条路径都"存在"</b>。
     * 这里降到 DEBUG 并返回真正的 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public Result<?> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.debug("路径不存在: {}", e.getResourcePath());
        return Result.fail(404, "资源不存在");
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
