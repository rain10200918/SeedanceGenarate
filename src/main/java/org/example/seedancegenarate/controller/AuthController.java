package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AuthRequest;
import org.example.seedancegenarate.dto.AuthResponse;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaScene;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.service.UserTokenService;
import org.example.seedancegenarate.util.IpUtils;
import org.example.seedancegenarate.util.TokenUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AppUserService appUserService;
    private final UserTokenService userTokenService;
    private final CaptchaSecurityService captchaSecurityService;

    @PostMapping("/register")
    public Result<AuthResponse> register(@RequestBody AuthRequest request, HttpServletRequest servletRequest) {
        String username = request == null ? null : request.getUsername();
        String password = request == null ? null : request.getPassword();
        String inviteCode = request == null ? null : request.getInviteCode();
        String captchaProof = request == null ? null : request.getCaptchaProof();
        var verified = captchaSecurityService.consumeProof(
                CaptchaScene.REGISTER,
                username,
                captchaProof,
                IpUtils.getClientIp(servletRequest)
        );
        return Result.success(appUserService.register(
                verified,
                password,
                inviteCode,
                servletRequest
        ));
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
