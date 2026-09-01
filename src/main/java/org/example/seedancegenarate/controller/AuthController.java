package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AuthRequest;
import org.example.seedancegenarate.dto.AuthResponse;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeRequest;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeResendRequest;
import org.example.seedancegenarate.dto.RegistrationPayloads.EmailCodeResponse;
import org.example.seedancegenarate.dto.RegistrationPayloads.RegisterRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.RegistrationEmailSessionService;
import org.example.seedancegenarate.service.UserTokenService;
import org.example.seedancegenarate.util.IpUtils;
import org.example.seedancegenarate.util.TokenUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AppUserService appUserService;
    private final UserTokenService userTokenService;
    private final CaptchaSecurityService captchaSecurityService;
    private final RegistrationEmailSessionService registrationEmailSessionService;

    @PostMapping("/register/email-code")
    public Result<EmailCodeResponse> sendRegistrationEmailCode(
            @RequestBody EmailCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        String requestId = request == null ? null : request.requestId();
        String username = request == null ? null : request.username();
        String email = request == null ? null : request.email();
        String inviteCode = request == null ? null : request.inviteCode();
        String captchaProof = request == null ? null : request.captchaProof();
        String clientIp = IpUtils.getClientIp(servletRequest);
        EmailCodeResponse recovered = registrationEmailSessionService.recover(
                requestId,
                username,
                email,
                inviteCode,
                clientIp
        );
        if (recovered != null) {
            return Result.success(recovered);
        }
        if (!StringUtils.hasText(captchaProof)) {
            throw new BusinessException(410, "发码请求已过期，请重新完成行为验证");
        }
        var verified = captchaSecurityService.consumeProof(
                CaptchaScene.REGISTER,
                username,
                captchaProof,
                clientIp
        );
        return Result.success(registrationEmailSessionService.send(
                requestId,
                verified,
                email,
                inviteCode
        ));
    }

    @PostMapping("/register/email-code/resend")
    public Result<EmailCodeResponse> resendRegistrationEmailCode(
            @RequestBody EmailCodeResendRequest request,
            HttpServletRequest servletRequest
    ) {
        return Result.success(registrationEmailSessionService.resend(
                request == null ? null : request.registrationTicket(),
                request == null ? null : request.email(),
                IpUtils.getClientIp(servletRequest)
        ));
    }

    @PostMapping("/register")
    public Result<AuthResponse> register(@RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        var verified = registrationEmailSessionService.consume(
                request,
                IpUtils.getClientIp(servletRequest)
        );
        AppUser user = appUserService.registerVerified(
                verified,
                request == null ? null : request.password(),
                servletRequest
        );
        String token;
        try {
            token = userTokenService.createToken(user.getId());
        } catch (RuntimeException e) {
            throw new BusinessException(503, "账号已创建，但自动登录失败，请直接登录", e);
        }
        user.setPassword(null);
        return Result.success(new AuthResponse(token, user));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@RequestBody AuthRequest request, HttpServletRequest servletRequest) {
        String username = request == null ? null : request.getUsername();
        String password = request == null ? null : request.getPassword();
        String captchaProof = request == null ? null : request.getCaptchaProof();
        var verified = captchaSecurityService.consumeProof(
                CaptchaScene.LOGIN,
                username,
                captchaProof,
                IpUtils.getClientIp(servletRequest)
        );
        return Result.success(appUserService.login(verified, password, servletRequest));
    }

    @PostMapping("/logout")
    public Result<Boolean> logout(HttpServletRequest request) {
        userTokenService.deleteToken(TokenUtils.resolveToken(request));
        return Result.success(true);
    }

    @GetMapping("/me")
    public Result<AppUser> me() {
        return Result.success(UserContext.getUser());
    }
}
