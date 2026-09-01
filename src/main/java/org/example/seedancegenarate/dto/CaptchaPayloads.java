package org.example.seedancegenarate.dto;

/** 登录与注册共用的滑块验证码边界契约。 */
public final class CaptchaPayloads {
    private CaptchaPayloads() {
    }

    public enum CaptchaScene {
        LOGIN,
        REGISTER
    }

    public record CaptchaGetRequest(
            String captchaType,
            String clientUid
    ) {
    }

    public record CaptchaGetResponse(
            String token,
            String originalImageBase64,
            String jigsawImageBase64,
            String secretKey,
            long expiresInSeconds
    ) {
    }

    public record CaptchaCheckRequest(
            String captchaType,
            String clientUid,
            String token,
            String pointJson,
            CaptchaScene scene,
            String username
    ) {
    }

    public record CaptchaCheckResponse(
            String captchaProof,
            long expiresInSeconds
    ) {
    }
}
