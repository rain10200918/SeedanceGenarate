package org.example.seedancegenarate.dto;

/** 注册邮箱验证的公开请求与响应契约。 */
public final class RegistrationPayloads {
    private RegistrationPayloads() {
    }

    public record EmailCodeRequest(
            String requestId,
            String username,
            String email,
            String inviteCode,
            String captchaProof
    ) {
    }

    public record EmailCodeResendRequest(
            String registrationTicket,
            String email
    ) {
    }

    public record RegisterRequest(
            String username,
            String email,
            String inviteCode,
            String password,
            String registrationTicket,
            String emailCode
    ) {
    }

    public record EmailCodeResponse(
            String registrationTicket,
            String maskedEmail,
            long expiresInSeconds,
            long resendAfterSeconds
    ) {
    }
}
