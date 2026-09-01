package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.dto.AuthRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeRequest;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.RegistrationEmailSessionService;
import org.example.seedancegenarate.service.UserTokenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaAuthGateTest {

    @Test
    void loginCannotReachUserServiceWithoutAConsumedProof() {
        // 【测什么】登录 proof 校验失败时，用户查询/BCrypt 所在的 AppUserService 完全不会被调用。
        // 【怎么算红】把 AuthController 的 consumeProof 移到 appUserService.login 之后，这条必须变红。
        AppUserService appUserService = mock(AppUserService.class);
        CaptchaSecurityService captchaSecurityService = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService registrationSessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(
                appUserService,
                mock(UserTokenService.class),
                captchaSecurityService,
                registrationSessions
        );
        HttpServletRequest servletRequest = requestFrom("203.0.113.8");
        AuthRequest request = authRequest("root", "secret1", null, "missing-proof");
        when(captchaSecurityService.consumeProof(
                CaptchaScene.LOGIN, "root", "missing-proof", "203.0.113.8"
        )).thenThrow(BusinessException.badRequest("验证码已失效，请重新验证"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.login(request, servletRequest)
        );

        assertEquals(400, error.getCode());
        verify(appUserService, never()).login(any(), any(), any());
    }

    @Test
    void registrationEmailCannotBeIssuedWithoutAConsumedProof() {
        // 【测什么】注册 proof 校验失败时，邮件会话、邀请码校验和写库都不会被调用。
        // 【怎么算红】发码接口绕过 consumeProof，或先调用邮箱会话服务再验 proof，这条必须变红。
        AppUserService appUserService = mock(AppUserService.class);
        CaptchaSecurityService captchaSecurityService = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService registrationSessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(
                appUserService,
                mock(UserTokenService.class),
                captchaSecurityService,
                registrationSessions
        );
        HttpServletRequest servletRequest = requestFrom("203.0.113.8");
        EmailCodeRequest request = new EmailCodeRequest(
                "123e4567-e89b-42d3-a456-426614174000",
                "new-user", "new@example.com", "invite", "missing-proof"
        );
        when(captchaSecurityService.consumeProof(
                CaptchaScene.REGISTER, "new-user", "missing-proof", "203.0.113.8"
        )).thenThrow(BusinessException.badRequest("验证码已失效，请重新验证"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.sendRegistrationEmailCode(request, servletRequest)
        );

        assertEquals(400, error.getCode());
        verify(registrationSessions, never()).send(any(), any(), any(), any());
        verify(appUserService, never()).registerVerified(any(), any(), any());
    }

    @Test
    void jsonNullBodyIsRejectedByTheCaptchaGateWithoutANullPointer() {
        // 【测什么】认证 JSON body 为 null 时仍进入验证码门禁并以业务 400 拒绝，不触发 NPE/用户服务。
        // 【怎么算红】恢复直接 request.getUsername() 的解引用，这条会抛 NullPointerException 并变红。
        AppUserService appUserService = mock(AppUserService.class);
        CaptchaSecurityService captchaSecurityService = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService registrationSessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(
                appUserService,
                mock(UserTokenService.class),
                captchaSecurityService,
                registrationSessions
        );
        HttpServletRequest servletRequest = requestFrom("203.0.113.8");
        when(captchaSecurityService.consumeProof(
                CaptchaScene.LOGIN, null, null, "203.0.113.8"
        )).thenThrow(BusinessException.badRequest("验证码已失效，请重新验证"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.login(null, servletRequest)
        );

        assertEquals(400, error.getCode());
        verify(appUserService, never()).login(any(), any(), any());
    }

    private static AuthRequest authRequest(String username, String password, String inviteCode, String proof) {
        AuthRequest request = new AuthRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setInviteCode(inviteCode);
        request.setCaptchaProof(proof);
        return request;
    }

    private static HttpServletRequest requestFrom(String remoteAddress) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddress);
        return request;
    }
}
