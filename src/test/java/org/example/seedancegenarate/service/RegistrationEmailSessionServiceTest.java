package org.example.seedancegenarate.service;

import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.RegisterRequest;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.InviteCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationEmailSessionServiceTest {
    private StringRedisTemplate redis;
    private TokenBucketRateLimitService rateLimits;
    private AppUserMapper users;
    private InviteCodeMapper invites;
    private JavaMailSender mailSender;
    private RegistrationEmailSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        rateLimits = mock(TokenBucketRateLimitService.class);
        users = mock(AppUserMapper.class);
        invites = mock(InviteCodeMapper.class);
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getInstance(new Properties()))
        );
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        when(rateLimits.tryAcquireDistributed(anyString(), any())).thenReturn(RateLimitResult.permitted());
        when(users.selectCount(any())).thenReturn(0L);
        when(invites.selectCount(any())).thenReturn(1L);
        service = new RegistrationEmailSessionService(
                redis,
                rateLimits,
                new RateLimitConfig(),
                users,
                invites,
                provider,
                "test:registration-email",
                "no-reply@example.com"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionOnlyBecomesUsableAfterSmtpAndRedisActivationBothSucceed() {
        // 【测什么】send 只扣邮箱/全局桶，先 PENDING、SMTP 成功后才 ACTIVE，且不回传明文码。
        // 【怎么算红】若重复扣 IP、SMTP 前写 ACTIVE、跳过激活结果或回传验证码，这条必须变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 600L);
        var verified = verified(" Alice ");

        var response = service.send(requestId(), verified, " Alice@Example.COM ", " INVITE ");

        assertEquals("a***e@example.com", response.maskedEmail());
        assertEquals(600, response.expiresInSeconds());
        assertEquals(60, response.resendAfterSeconds());
        var order = inOrder(redis, mailSender);
        order.verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        order.verify(mailSender).send(any(MimeMessage.class));
        order.verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        var limitKey = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimits, times(2)).tryAcquireDistributed(limitKey.capture(), any());
        assertTrue(limitKey.getAllValues().get(0).startsWith("registration-email:address:"));
        assertEquals("registration-email:global", limitKey.getAllValues().get(1));
        var scriptArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), scriptArgs.capture());
        Object[] initArgs = scriptArgs.getAllValues().get(0);
        assertEquals(5, initArgs.length);
        assertEquals("600", initArgs[3]);
        assertEquals("60000", initArgs[4]);
        verify(redis, never()).delete(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void smtpFailureConditionallyCleansItsPendingSessionAndReturns503() {
        // 【测什么】SMTP 失败时用第二次 Lua 调用按本次 codeMac 清 PENDING，且不做无条件 DEL。
        // 【怎么算红】删掉异常清理、退回 redis.delete，或把异常吞成成功响应，这条必须变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.send(
                requestId(), verified("alice"), "alice@example.com", "INVITE"
        ));

        assertEquals(503, error.getCode());
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activationFailureConditionallyCleansThePendingSessionAfterMailWasAccepted() {
        // 【测什么】SMTP 已接受但激活失败时，用第三次 Lua 按本次 codeMac 清 PENDING，且返回 503。
        // 【怎么算红】忽略 ACTIVATE 结果、漏掉条件清理或退回无条件 DEL，这条必须变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 0L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.send(
                requestId(), verified("alice"), "alice@example.com", "INVITE"
        ));

        assertEquals(503, error.getCode());
        verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void aNonRegisterVerifiedAttemptCannotIssueMail() {
        // 【测什么】邮箱会话签发的类型先决条件必须是 REGISTER VerifiedAttempt，LOGIN proof 不能换邮件。
        // 【怎么算红】去掉 scene 守卫后会调用 Redis/SMTP，本测试的零交互断言变红。
        var verified = verified("alice");
        when(verified.scene()).thenReturn(CaptchaScene.LOGIN);

        BusinessException error = assertThrows(BusinessException.class, () -> service.send(
                requestId(), verified, "alice@example.com", "INVITE"
        ));

        assertEquals(400, error.getCode());
        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void invalidPublicMailDomainsAreRejectedBeforeLimitsOrRedis() {
        // 【测什么】单标签域、标签首尾横线和域字面量不被 Jakarta Mail 的宽松校验放行。
        // 【怎么算红】只依赖 InternetAddress.validate 时至少 a@example 会进入限流并使零交互断言变红。
        for (String email : new String[]{
                "a@example", "a@-example.com", "a@example-.com", "a@[127.0.0.1]"
        }) {
            BusinessException error = assertThrows(BusinessException.class, () -> service.send(
                    requestId(), verified("alice"), email, "INVITE"
            ));
            assertEquals(400, error.getCode(), email);
        }
        verify(rateLimits, never()).tryAcquireDistributed(anyString(), any());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedWrongCodeReturns410WithoutCreatingAVerifiedRegistration() {
        // 【测什么】Lua 报告第 5 次输错已销毁 ticket 时，对外是会话耗尽 410。
        // 【怎么算红】若把 -4 当普通 400、或仍构造 VerifiedRegistration，本测试必须变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-4L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.consume(
                request("123456"), "203.0.113.8"
        ));

        assertEquals(410, error.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void changedBoundRegistrationDataBurnsTheTicketAsBadRequest() {
        // 【测什么】Lua 报告用户名/邮箱/邀请码绑定不一致时拒绝并要求重新验证。
        // 【怎么算红】若忽略 -2 或允许请求进入 AppUserService，本测试会错误返回成功。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-2L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.consume(
                request("123456"), "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resendLimitExhaustionReturns410WithoutSendingAnotherMail() {
        // 【测什么】初发加两次重发后 Lua 的 -3 对外转成耗尽 410，绝不再调用 SMTP。
        // 【怎么算红】若忽略最多三封限制、把 -3 当成功 TTL，SMTP 会被调用且本测试变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, -3L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.resend(
                "a".repeat(43), "alice@example.com", "203.0.113.8"
        ));

        assertEquals(410, error.getCode());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resendCooldownReturns429WithoutSendingAnotherMail() {
        // 【测什么】60 秒冷却由 Redis 时间判定，Lua 的 -4 对外保持可重试的 429。
        // 【怎么算红】若绕过冷却或把 -4 视作正常 TTL，本测试会错误触发 SMTP。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, -4L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.resend(
                "a".repeat(43), "alice@example.com", "203.0.113.8"
        ));

        assertEquals(429, error.getCode());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void randomOrExpiredTicketCannotConsumeVictimEmailOrGlobalBuckets() {
        // 【测什么】重发先只扣来源 IP 桶；Redis 判 ticket 不存在后，不触碰受害邮箱桶和全局桶。
        // 【怎么算红】若恢复 requireMailLimits 在 ticket 校验前一次扣三桶，限流服务会被调用 3 次而变红。
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.resend(
                "a".repeat(43), "victim@example.com", "203.0.113.8"
        ));

        assertEquals(410, error.getCode());
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimits, times(1)).tryAcquireDistributed(key.capture(), any());
        assertTrue(key.getValue().startsWith("registration-email:ip:"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeSessionRecoveryReturnsDeterministicTicketWithOnlyCallerIpLimit() throws Exception {
        // 【测什么】响应丢失后恢复原 ticket/TTL/冷却，只扣调用方 IP，不重发或扣邮箱/全局桶。
        // 【怎么算红】若 ticket 随机、recover 复用 send 或未保护 Redis，ticket/SMTP/限流次数会变红。
        doReturn(1L, 600L, List.of(1L, 599L, 17L)).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        var first = service.send(
                requestId(), verified("alice"), "alice@example.com", "INVITE"
        );
        clearInvocations(redis, rateLimits, mailSender);

        var recovered = service.recover(
                requestId(), "alice", "alice@example.com", "INVITE", "203.0.113.8"
        );

        assertEquals(expectedTicket(), first.registrationTicket());
        assertEquals(first.registrationTicket(), recovered.registrationTicket());
        assertEquals(599, recovered.expiresInSeconds());
        assertEquals(17, recovered.resendAfterSeconds());
        assertOnlyCallerIpLimitWasUsed();
        verifyNoInteractions(mailSender);
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryWithWrongIdentityBurnsTheDeterministicSession() {
        // 【测什么】同 requestId 但身份不一致映射 400；仅先扣调用方 IP，Lua 原子烧票且不发信。
        // 【怎么算红】若只按 requestId 返回会话，错误身份会拿到 ticket 并使本测试不再抛 400。
        doReturn(List.of(-2L)).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.recover(
                requestId(), "mallory", "alice@example.com", "INVITE", "203.0.113.8"
        ));

        assertEquals(400, error.getCode());
        assertOnlyCallerIpLimitWasUsed();
        verifyNoInteractions(mailSender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRecoveryReturnsNullAfterOnlyCallerIpLimit() {
        // 【测什么】随机/过期 requestId 只扣调用方 IP，不消耗受害邮箱/全局额度，也不发信。
        // 【怎么算红】若 recover 不限 IP或直接调用三桶 send，限流次数/键类型断言会变红。
        doReturn(List.of(0L)).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        var recovered = service.recover(
                requestId(), "alice", "victim@example.com", "INVITE", "203.0.113.8"
        );

        assertNull(recovered);
        assertOnlyCallerIpLimitWasUsed();
        verifyNoInteractions(mailSender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingRecoveryReturns429WithoutProofOrMailWork() {
        // 【测什么】并发首次发信尚处 PENDING 时，恢复请求得到 429 而非重复 INIT/SMTP。
        // 【怎么算红】若 PENDING 被当成 miss，controller 会继续消费 proof，服务也可能重复发信。
        doReturn(List.of(-1L)).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        BusinessException error = assertThrows(BusinessException.class, () -> service.recover(
                requestId(), "alice", "alice@example.com", "INVITE", "203.0.113.8"
        ));

        assertEquals(429, error.getCode());
        assertOnlyCallerIpLimitWasUsed();
        verifyNoInteractions(mailSender);
    }

    @Test
    void nonCanonicalOrNonV4RequestIdIsRejectedBeforeRedis() {
        // 【测什么】requestId 必须是精确小写 canonical UUID v4，空格/大写/v1 都不能派生 ticket。
        // 【怎么算红】直接 UUID.fromString 后不校验 canonical/version 会访问 Redis 并使零交互断言变红。
        for (String requestId : new String[]{
                "not-a-uuid",
                "123e4567-e89b-12d3-a456-426614174000",
                "123E4567-E89B-42D3-A456-426614174000",
                " " + requestId()
        }) {
            BusinessException error = assertThrows(BusinessException.class, () -> service.recover(
                    requestId, "alice", "alice@example.com", "INVITE", "203.0.113.8"
            ));
            assertEquals(400, error.getCode(), requestId);
        }
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verifyNoInteractions(rateLimits, mailSender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void initCompetitionRecoversActiveWinnerWithoutSecondSmtp() throws Exception {
        // 【测什么】确定性 key 的 INIT 输掉竞争后必须恢复赢家 ACTIVE 会话，不能继续发第二封邮件。
        // 【怎么算红】若 INIT=0 仍 deliverAndActivate 或直接报随机碰撞，本测试会 SMTP/503 而变红。
        doReturn(0L, List.of(1L, 598L, 12L)).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        var response = service.send(
                requestId(), verified("alice"), "alice@example.com", "INVITE"
        );

        assertEquals(expectedTicket(), response.registrationTicket());
        assertEquals(598, response.expiresInSeconds());
        assertEquals(12, response.resendAfterSeconds());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private static RegisterRequest request(String emailCode) {
        return new RegisterRequest(
                "alice",
                "alice@example.com",
                "INVITE",
                "secret1",
                "a".repeat(43),
                emailCode
        );
    }

    private static CaptchaSecurityService.VerifiedAttempt verified(String username) {
        CaptchaSecurityService.VerifiedAttempt verified = mock(CaptchaSecurityService.VerifiedAttempt.class);
        when(verified.scene()).thenReturn(CaptchaScene.REGISTER);
        when(verified.username()).thenReturn(username);
        return verified;
    }

    private void assertOnlyCallerIpLimitWasUsed() {
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rateLimits, times(1)).tryAcquireDistributed(key.capture(), any());
        assertTrue(key.getValue().startsWith("registration-email:ip:"));
    }

    private static String requestId() {
        return "123e4567-e89b-42d3-a456-426614174000";
    }

    private static String expectedTicket() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("registration-ticket\0" + requestId()).getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
