package org.example.seedancegenarate.controller;

import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.RegistrationEmailSessionService;
import org.example.seedancegenarate.service.UserTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthLogoutControllerTest {

    @Test
    void logoutRevokesOnlyTheTokenResolvedFromTheCurrentRequest() {
        // 【测什么】登出沿用 TokenUtils 优先级，只删当前 Authorization token，不读用户或验证码服务。
        // 【怎么算红】把 logout 改为按 userId 批量撤销，或绕过 TokenUtils 读另一个 token，这条必须变红。
        AppUserService users = mock(AppUserService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        CaptchaSecurityService captcha = mock(CaptchaSecurityService.class);
        RegistrationEmailSessionService registrationSessions = mock(RegistrationEmailSessionService.class);
        AuthController controller = new AuthController(users, tokens, captcha, registrationSessions);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        request.addHeader("Authorization", "Bearer current-token");
        request.addHeader("X-Token", "other-device-token");
        request.setParameter("token", "query-token");

        var result = controller.logout(request);

        assertTrue(result.getData());
        verify(tokens).deleteToken("current-token");
        verifyNoInteractions(users, captcha, registrationSessions);
    }
}
