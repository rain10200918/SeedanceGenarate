package org.example.seedancegenarate.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.entity.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * UI 侧统一异常兜底（Result 契约）。对外 API 的 ApiException 由
 * {@code exception.ApiExceptionHandler} 优先处理（@Order 最高）。
 * 500 一律带堆栈记日志，便于排障。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException exception) {
        if ("请先登录".equals(exception.getMessage())) {
            return Result.unauthorized(exception.getMessage());
        }
        log.error("接口异常: {}", exception.getMessage(), exception);
        return Result.fail(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception exception) {
        log.error("接口异常: {}", exception.getMessage(), exception);
        return Result.fail(exception.getMessage());
    }
}
