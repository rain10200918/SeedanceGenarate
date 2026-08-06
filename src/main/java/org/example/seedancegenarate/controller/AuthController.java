package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.context.UserContext;
import org.example.seedancegenarate.dto.AuthRequest;
import org.example.seedancegenarate.dto.AuthResponse;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.UserTokenService;
import org.example.seedancegenarate.util.TokenUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AppUserService appUserService;
    private final UserTokenService userTokenService;

    @PostMapping("/register")
    public Result<AuthResponse> register(@RequestBody AuthRequest request, HttpServletRequest servletRequest) {
        return Result.success(appUserService.register(request.getUsername(), request.getPassword(), request.getInviteCode(), servletRequest));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@RequestBody AuthRequest request, HttpServletRequest servletRequest) {
        return Result.success(appUserService.login(request.getUsername(), request.getPassword(), servletRequest));
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
