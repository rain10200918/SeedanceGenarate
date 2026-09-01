package org.example.seedancegenarate.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void malformedJsonOrUnknownCaptchaSceneReturns400() {
        // 【测什么】未知验证码场景导致的 JSON 反序列化错误统一返回业务 400。
        // 【怎么算红】删除 HttpMessageNotReadableException 专用 handler，这条将无法编译或落回 500。
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Cannot deserialize CaptchaScene",
                new MockHttpInputMessage("{}".getBytes())
        );

        Result<?> res = handler.handleMessageNotReadable(ex);

        assertEquals(400, res.getCode());
        assertEquals("请求参数不合法", res.getMessage());
    }

    @Test
    void malformedAuthJsonDoesNotCopySubmittedSecretsIntoLogs() {
        // 【测什么】Jackson 异常即使带有密码/proof 片段，认证日志也只能写固定文本。
        // 【怎么算红】把 e.getMessage() 再拼进 WARN，捕获到的日志将包含 secret 并使本测试变红。
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                    "Cannot deserialize password=super-secret captchaProof=proof-secret",
                    new MockHttpInputMessage("{}".getBytes())
            );

            handler.handleMessageNotReadable(ex);

            String rendered = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(rendered.contains("super-secret"));
            assertFalse(rendered.contains("proof-secret"));
            assertEquals(true, rendered.contains("请求体 JSON 不合法"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
