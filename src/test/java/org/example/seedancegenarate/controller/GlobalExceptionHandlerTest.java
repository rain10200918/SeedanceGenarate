package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessExceptionReturnsCustomCodeAndMessage() {
        BusinessException ex = BusinessException.forbidden("该模型未对普通用户开放");
        Result<?> res = handler.handleBusinessException(ex);
        assertEquals(403, res.getCode());
        assertEquals("该模型未对普通用户开放", res.getMessage());
    }

    @Test
    void handleIllegalArgumentExceptionReturns400() {
        IllegalArgumentException ex = new IllegalArgumentException("时长必须在 5 到 15 秒之间");
        Result<?> res = handler.handleIllegalArgument(ex);
        assertEquals(400, res.getCode());
        assertEquals("时长必须在 5 到 15 秒之间", res.getMessage());
    }

    @Test
    void handleMaxUploadSizeExceededReturnsFriendlyMessage() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(50 * 1024 * 1024);
        Result<?> res = handler.handleMaxUploadSizeExceeded(ex);
        assertEquals(400, res.getCode());
        assertEquals("上传文件体积过大，请压缩或裁剪后重试", res.getMessage());
    }

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");
        Result<?> res = handler.handleMethodNotSupported(ex);
        assertEquals(405, res.getCode());
        assertEquals("不支持的请求方式: POST", res.getMessage());
    }

    @Test
    void handleMissingParamReturns400() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("taskId", "String");
        Result<?> res = handler.handleMissingParam(ex);
        assertEquals(400, res.getCode());
        assertEquals("缺少必要参数: taskId", res.getMessage());
    }

    @Test
    void handleUnauthorizedRuntimeExceptionReturns401() {
        RuntimeException ex = new RuntimeException("请先登录");
        Result<?> res = handler.handleRuntimeException(ex);
        assertEquals(401, res.getCode());
        assertEquals("请先登录", res.getMessage());
    }

    @Test
    void handleUnknownExceptionReturns500WithFriendlyMessage() {
        Exception ex = new Exception("DB socket timeout");
        Result<?> res = handler.handleException(ex);
        assertEquals(500, res.getCode());
        assertEquals("系统繁忙，请稍后重试", res.getMessage());
    }
}
