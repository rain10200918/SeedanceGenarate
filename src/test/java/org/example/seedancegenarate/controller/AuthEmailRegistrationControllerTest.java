package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeRequest;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeResponse;
import org.example.seedancegenarate.dto.RegistrationPayloads.RegisterRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.RegistrationEmailSessionService;
import org.example.seedancegenarate.service.UserTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthEmailRegistrationControllerTest {

    @Test
    void lostResponseRecoversTheSameActiveSessionWithoutConsumingProofAgain() {
        // 【测什么】首次 miss 才消费 proof；响应丢失后同 requestId 直接恢复原 ticket，不再次进入发信链。
        // 【怎么算红】若先消费 proof 再恢复，第二次调用 captcha/send 次数会从 1 变 2 并使测试变红。
        AppUserService users = mock(AppUserService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService sessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(users, tokens, captcha, sessions);
        MockHttpServletRequest servletRequest = request();
        EmailCodeRequest body = new EmailCodeRequest(
                requestId(), " alice ", "Alice@Example.com", " INVITE ", "proof"
        );
        EmailCodeRequest recovery = new EmailCodeRequest(
                requestId(), " alice ", "Alice@Example.com", " INVITE ", null
        );
        CaptchaSecurityService.VerifiedAttempt verified = mock(CaptchaSecurityService.VerifiedAttempt.class);
        EmailCodeResponse sent = new EmailCodeResponse("ticket", "a***e@example.com", 600, 60);
        when(sessions.recover(
                requestId(), " alice ", "Alice@Example.com", " INVITE ", "203.0.113.8"
        ))
                .thenReturn(null, sent);
        when(captcha.consumeProof(CaptchaScene.REGISTER, " alice ", "proof", "203.0.113.8"))
                .thenReturn(verified);
        when(sessions.send(requestId(), verified, "Alice@Example.com", " INVITE "))
                .thenReturn(sent);

        var first = controller.sendRegistrationEmailCode(body, servletRequest);
        var restored = controller.sendRegistrationEmailCode(recovery, servletRequest);

        assertEquals(sent, first.getData());
        assertEquals(sent, restored.getData());
        InOrder order = inOrder(sessions, captcha);
        order.verify(sessions).recover(
                requestId(), " alice ", "Alice@Example.com", " INVITE ", "203.0.113.8"
        );
        order.verify(captcha).consumeProof(CaptchaScene.REGISTER, " alice ", "proof", "203.0.113.8");
        order.verify(sessions).send(requestId(), verified, "Alice@Example.com", " INVITE ");
        order.verify(sessions).recover(
                requestId(), " alice ", "Alice@Example.com", " INVITE ", "203.0.113.8"
        );
        verify(captcha, times(1)).consumeProof(CaptchaScene.REGISTER, " alice ", "proof", "203.0.113.8");
        verify(sessions, times(1)).send(requestId(), verified, "Alice@Example.com", " INVITE ");
    }

    @Test
    void missingRecoveryWithoutProofReturns410BeforeCaptchaOrSend() {
        // 【测什么】恢复不到会话且 captchaProof 为空时明确 410，不拿空 proof 触发验证码门禁或发信限流。
        // 【怎么算红】若仍无条件 consumeProof，captcha mock 会产生交互并使测试变红。
        AppUserService users = mock(AppUserService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService sessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(users, tokens, captcha, sessions);
        EmailCodeRequest recovery = new EmailCodeRequest(
                requestId(), "alice", "alice@example.com", "INVITE", " "
        );
        when(sessions.recover(
                requestId(), "alice", "alice@example.com", "INVITE", "203.0.113.8"
        ))
                .thenReturn(null);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.sendRegistrationEmailCode(recovery, request())
        );

        assertEquals(410, error.getCode());
        verifyNoInteractions(captcha);
        verify(sessions, never()).send(anyString(), any(), anyString(), anyString());
    }

    @Test
    void tokenIsCreatedOnlyAfterTransactionalRegistrationReturns() {
        // 【测什么】最终注册先消费邮箱会话，再等事务型 registerVerified 返回，最后才签 Redis token。
        // 【怎么算红】把 createToken 移进 AppUserService 的注册事务，或在 registerVerified 前调用它，这条必须变红。
        AppUserService users = mock(AppUserService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService sessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(users, tokens, captcha, sessions);
        RegisterRequest body = registerRequest();
        var verified = mock(RegistrationEmailSessionService.VerifiedRegistration.class);
        AppUser user = new AppUser();
        user.setId(42L);
        user.setUsername("alice");
        when(sessions.consume(body, "203.0.113.8")).thenReturn(verified);
        when(tokens.createToken(42L)).thenReturn("token-42");
        MockHttpServletRequest servletRequest = request();
        when(users.registerVerified(verified, "secret1", servletRequest)).thenReturn(user);

        var response = controller.register(body, servletRequest);

        assertEquals("token-42", response.getData().getToken());
        InOrder order = inOrder(sessions, users, tokens);
        order.verify(sessions).consume(body, "203.0.113.8");
        order.verify(users).registerVerified(verified, "secret1", servletRequest);
        order.verify(tokens).createToken(42L);
    }

    @Test
    void tokenFailureSaysTheAccountAlreadyExistsAndCanBeRecoveredByLogin() {
        // 【测什么】数据库已提交后若 token Redis 失败，返回 503 必须明确账号已创建并引导直接登录。
        // 【怎么算红】若 controller 原样透出“注册失败”或试图回滚已经返回的 registerVerified，这条必须变红。
        AppUserService users = mock(AppUserService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService sessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(users, tokens, captcha, sessions);
        RegisterRequest body = registerRequest();
        var verified = mock(RegistrationEmailSessionService.VerifiedRegistration.class);
        AppUser user = new AppUser();
        user.setId(42L);
        MockHttpServletRequest servletRequest = request();
        when(sessions.consume(body, "203.0.113.8")).thenReturn(verified);
        when(users.registerVerified(verified, "secret1", servletRequest)).thenReturn(user);
        when(tokens.createToken(42L)).thenThrow(new BusinessException(503, "登录服务暂不可用"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.register(body, servletRequest)
        );

        assertEquals(503, error.getCode());
        assertEquals("账号已创建，但自动登录失败，请直接登录", error.getMessage());
        verify(users).registerVerified(verified, "secret1", servletRequest);
    }

    private static RegisterRequest registerRequest() {
        return new RegisterRequest(
                "alice", "alice@example.com", "INVITE", "secret1", "ticket", "123456"
        );
    }

    private static String requestId() {
        return "123e4567-e89b-42d3-a456-426614174000";
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        return request;
    }
}
