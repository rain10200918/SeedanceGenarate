package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.entity.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SSE 的异步生命周期事件不能被当成服务端错误。
 * <p>
 * 2026-08-26 线上实测：客户端断开重连 → {@code IllegalStateException: Cannot start async} →
 * 落到状态异常 handler → 它往一个 Content-Type 已经是 {@code text/event-stream} 的响应里写
 * {@code Result} → <b>没有转换器，异常处理器自己再炸一次</b>。
 * 一次客户端抖动打出 4 条日志、3 份完整堆栈。
 */
class SseAsyncLifecycleNotAnErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void cannotStartAsyncReturnsNoBody() {
        // 【测什么】Cannot start async 时返回 204 且**不带 body**
        // 【怎么算红】仍然返回 Result body —— 响应已被钉成 text/event-stream，
        //            写 Result 必然触发 HttpMessageNotWritableException，
        //            异常处理器自己炸，一次断线打出三份堆栈把日志淹掉
        ResponseEntity<?> response = handler.handleIllegalState(
                new IllegalStateException("Cannot start async: [ERROR]"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody(), "不能带 body，实际=" + response.getBody());
    }

    @Test
    void businessStateAssertionStillReturnsResult() {
        // 【测什么】普通业务断言（钱包那类）行为一字未变：200 + Result.fail(400, 原文)
        // 【怎么算红】把所有 IllegalStateException 都静默成 204 ——
        //            「余额不足」这类提示前端再也收不到，用户点了没反应也不知道为什么
        ResponseEntity<?> response = handler.handleIllegalState(
                new IllegalStateException("钱包冻结余额不足，无法解冻: userId=22, taskId=764"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Result<?> body = assertInstanceOf(Result.class, response.getBody());
        assertNotNull(body);
        assertEquals(400, body.getCode());
        assertEquals("钱包冻结余额不足，无法解冻: userId=22, taskId=764", body.getMessage());
    }

    @Test
    void asyncRequestTimeoutIsHandledWithoutBody() throws Exception {
        // 【测什么】SSE emitter 30 分钟到期（AsyncRequestTimeoutException）有**专属处理器**，
        //          且该处理器不返回 body
        // 【怎么算红】删掉 handleAsyncRequestTimeout —— 它是 RuntimeException 子类、
        //          message 为 null，会一路掉进 #10 handleRuntimeException：
        //          打 ~20 行 ERROR 堆栈，再往 text/event-stream 写 Result 二次抛异常。
        //          每条 SSE 连接每 30 分钟来一次，是线上 ERROR 噪音的最大来源。
        //          2026-08-26 20:01:47 实测，而当天早些时候按 message 判定的修复没盖住它
        java.lang.reflect.Method m = GlobalExceptionHandler.class.getMethod(
                "handleAsyncRequestTimeout",
                org.springframework.web.context.request.async.AsyncRequestTimeoutException.class);

        assertEquals(void.class, m.getReturnType(),
                "必须返回 void（不写 body），实际=" + m.getReturnType());
        m.invoke(handler, new org.springframework.web.context.request.async
                .AsyncRequestTimeoutException());
    }

    @Test
    void springRoutesAsyncTimeoutAwayFromTheGenericRuntimeHandler() {
        // 【测什么】用 Spring **自己的**解析器验证分派结果：AsyncRequestTimeoutException
        //          落到 handleAsyncRequestTimeout，而不是 #10 handleRuntimeException
        // 【怎么算红】删掉专属处理器 —— 解析器会退到 handleRuntimeException，
        //          那条路 log.error 打全栈 + 返回 Result body，正是 2026-08-26 20:01:47
        //          线上那串「ERROR 全栈 → No converter for [Result] with preset
        //          Content-Type 'text/event-stream' → 处理器自己再炸」的完整复现
        org.springframework.web.method.annotation.ExceptionHandlerMethodResolver resolver =
                new org.springframework.web.method.annotation.ExceptionHandlerMethodResolver(
                        GlobalExceptionHandler.class);

        java.lang.reflect.Method resolved = resolver.resolveMethod(
                new org.springframework.web.context.request.async.AsyncRequestTimeoutException());

        assertNotNull(resolved, "没有任何处理器接住它 —— 会掉到容器兜底");
        assertEquals("handleAsyncRequestTimeout", resolved.getName(),
                "被错误的处理器接走了，实际=" + resolved.getName());
    }

    @Test
    void ordinaryRuntimeExceptionsStillReachTheGenericHandler() {
        // 【测什么】新增的专属处理器没有顺手抢走普通运行时异常
        // 【怎么算红】把它的入参放宽成 RuntimeException —— 所有业务异常都被静默成
        //          「不写 body 的 DEBUG 日志」，前端收不到任何错误信息，
        //          服务端也不再记录真正的故障
        org.springframework.web.method.annotation.ExceptionHandlerMethodResolver resolver =
                new org.springframework.web.method.annotation.ExceptionHandlerMethodResolver(
                        GlobalExceptionHandler.class);

        java.lang.reflect.Method resolved =
                resolver.resolveMethod(new RuntimeException("下游服务不可用"));

        assertEquals("handleRuntimeException", resolved.getName(),
                "普通运行时异常必须仍走兜底，实际=" + resolved.getName());
    }

    @Test
    void nullMessageDoesNotBlowUp() {
        // 【测什么】message 为 null 时不 NPE
        // 【怎么算红】直接 e.getMessage().contains(...) —— 异常处理器里抛 NPE，
        //            原始异常被彻底掩盖，排障时只看得到一个 NullPointerException
        ResponseEntity<?> response = handler.handleIllegalState(new IllegalStateException());

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
