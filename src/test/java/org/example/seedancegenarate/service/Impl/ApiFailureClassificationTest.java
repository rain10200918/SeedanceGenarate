package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.exception.ApiException;
import org.example.seedancegenarate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ApiFailureClassificationTest {
    private final ApiVideoServiceImpl service = new ApiVideoServiceImpl(null, null, null, null, null);

    private ApiException classify(Exception failure) {
        return ReflectionTestUtils.invokeMethod(service, "toApiException", failure, "test-model");
    }

    private void assertCodes(Exception failure, String code, HttpStatus status) {
        ApiException mapped = classify(failure);
        assertEquals(code, mapped.getCode());
        assertEquals(status, mapped.getHttpStatus());
        assertEquals(code, ReflectionTestUtils.invokeMethod(service, "resolveErrorCode", failure));
        assertEquals(status, ReflectionTestUtils.invokeMethod(service, "resolveHttpCode", failure));
    }

    // 【测什么】类型化参数错误与429的响应和调用日志分类一致。
    // 【怎么算红】日志继续按“不支持”或未识别ApiException分类，码或状态断言失败。
    @Test void typedFailuresKeepResponseAndLogAligned() {
        assertCodes(BusinessException.badRequest("不支持的比例"), "VALIDATION_ERROR", HttpStatus.BAD_REQUEST);
        assertCodes(ApiException.rateLimited(), "RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS);
        assertCodes(ApiException.concurrencyLimited(2, 2), "CONCURRENCY_LIMIT", HttpStatus.TOO_MANY_REQUESTS);
        assertCodes(new ApiException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT, "请求参数不同"),
                "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
    }

    // 【测什么】仅两个已知固定业务语义兼容旧Runtime，不按任意子串猜模型或余额。
    // 【怎么算红】恢复contains匹配后，带数据库诊断的异常被误判成402或403。
    @Test void legacyMessagesAreExactAndUnknownFailuresStayInternal() {
        assertCodes(new RuntimeException("余额不足，请先充值"), "INSUFFICIENT_BALANCE", HttpStatus.PAYMENT_REQUIRED);
        assertCodes(new RuntimeException("该模型未开放"), "MODEL_NOT_OPEN", HttpStatus.FORBIDDEN);
        for (String message : new String[]{"不支持的驱动", "SQL余额不足，请先充值", "数据库：该模型未开放"}) {
            assertCodes(new RuntimeException(message), "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // 【测什么】ApiException也不能携带内部URL、凭据或SQL原文穿透到客户端。
    // 【怎么算红】直接return原ApiException或internal(e.getMessage())将泄漏secret。
    @Test void internalAndProviderMessagesAreNeverTrusted() {
        String secret = "https://user:secret@private/db?token=secret SELECT password FROM users";
        for (Exception failure : new Exception[]{new RuntimeException(secret), ApiException.internal(secret),
                ApiException.providerUnavailable(secret), ApiException.validation(secret)}) {
            ApiException mapped = classify(failure);
            assertFalse(mapped.getMessage().contains("secret"));
            assertFalse(mapped.getMessage().contains("https://"));
            assertFalse(mapped.getMessage().contains("SELECT"));
            assertEquals(mapped.getCode(), ReflectionTestUtils.invokeMethod(service, "resolveErrorCode", failure));
            assertEquals(mapped.getHttpStatus(), ReflectionTestUtils.invokeMethod(service, "resolveHttpCode", failure));
        }
    }
}
