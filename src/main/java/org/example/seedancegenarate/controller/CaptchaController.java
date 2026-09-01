package org.example.seedancegenarate.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaCheckRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaCheckResponse;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaGetRequest;
import org.example.seedancegenarate.dto.CaptchaPayloads.CaptchaGetResponse;
import org.example.seedancegenarate.entity.Result;
import org.example.seedancegenarate.service.CaptchaSecurityService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {
    private final CaptchaSecurityService captchaSecurityService;

    @PostMapping("/get")
    public Result<CaptchaGetResponse> get(
            @RequestBody CaptchaGetRequest request,
            HttpServletRequest servletRequest
    ) {
        return Result.success(captchaSecurityService.issueChallenge(
                request,
                IpUtils.getClientIp(servletRequest)
        ));
    }

    @PostMapping("/check")
    public Result<CaptchaCheckResponse> check(
            @RequestBody CaptchaCheckRequest request,
            HttpServletRequest servletRequest
    ) {
        return Result.success(captchaSecurityService.checkChallenge(
                request,
                IpUtils.getClientIp(servletRequest)
        ));
    }
}
