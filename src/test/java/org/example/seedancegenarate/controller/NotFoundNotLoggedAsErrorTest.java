package org.example.seedancegenarate.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径不存在必须当 404 处理，不能落到「系统未知错误」兜底。
 * <p>
 * 2026-08-26 线上实测：机器正被每秒一发地扫 VPN/邮件网关漏洞
 * （global-protect/login.esp、remote/login、mifs/login.jsp、owa …）。
 * 改动前每一发都打一份 ~50 行 ERROR 堆栈并返回 200 —— 日志被淹，真错误埋在里面找不到。
 */
class NotFoundNotLoggedAsErrorTest {

    private Method handlerFor(Class<? extends Throwable> exceptionType) {
        return Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> {
                    ExceptionHandler a = AnnotatedElementUtils.findMergedAnnotation(m, ExceptionHandler.class);
                    return a != null && Arrays.asList(a.value()).contains(exceptionType);
                })
                .findFirst().orElse(null);
    }

    @Test
    void noResourceFoundHasItsOwnHandler() {
        // 【测什么】NoResourceFoundException 有专属 handler，不再落到 Exception 兜底
        // 【怎么算红】删掉这个 handler —— 每一发扫描探测又变回一份 50 行 ERROR 堆栈，
        //            持续扫描下真正的系统错误会被彻底埋掉
        assertNotNull(handlerFor(NoResourceFoundException.class),
                "GlobalExceptionHandler 必须显式处理 NoResourceFoundException");
    }

    @Test
    void noResourceFoundReturnsRealNotFoundStatus() {
        // 【测什么】它带 @ResponseStatus(404)，返回真正的 404
        // 【怎么算红】仍返回 200 —— 对扫描器来说"每条路径都存在"，等于主动邀请更深的探测；
        //            监控侧也看不出 404 率
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(
                handlerFor(NoResourceFoundException.class), ResponseStatus.class);

        assertNotNull(status, "缺 @ResponseStatus，Spring 会返回 200");
        assertEquals(HttpStatus.NOT_FOUND, status.value());
    }

    @Test
    void unknownErrorFallbackStillExists() {
        // 【测什么】没有把 Exception 兜底一起删掉——真出系统错误时仍要带堆栈记 ERROR
        // 【怎么算红】兜底没了 —— 未知异常直接抛给 Tomcat，返回默认错误页且日志里没有堆栈，
        //            排障时什么线索都没有
        Method fallback = handlerFor(Exception.class);
        assertNotNull(fallback, "Exception 兜底不能删");
        assertTrue(fallback.getName().contains("handleException"), "实际=" + fallback.getName());
    }
}
