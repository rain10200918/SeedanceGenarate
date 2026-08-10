package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.TokenCacheService;
import org.example.seedancegenarate.service.UserTokenService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class UserTokenServiceImpl implements UserTokenService {
    private final AppUserService appUserService;
    private final TokenCacheService tokenCacheService;
    private final AuthTokenProperties authTokenProperties;

    public UserTokenServiceImpl(@Lazy AppUserService appUserService,
                                TokenCacheService tokenCacheService,
                                AuthTokenProperties authTokenProperties) {
        this.appUserService = appUserService;
        this.tokenCacheService = tokenCacheService;
        this.authTokenProperties = authTokenProperties;
    }

    @Override
    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long ttlSeconds = Math.max(authTokenProperties.getTtlSeconds(), 1);
        Instant expireAt = Instant.now().plusSeconds(ttlSeconds);
        if (!tokenCacheService.put(token, userId, expireAt, ttlSeconds)) {
            throw new IllegalStateException("登录服务暂不可用，请稍后重试");
        }
        return token;
    }

    @Override
    public AppUser getUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TokenCacheService.CachedToken cached = tokenCacheService.getAndRefreshIfNeeded(
                token,
                authTokenProperties.getTtlSeconds(),
                authTokenProperties.getRefreshThresholdSeconds()
        );
        if (cached == null) {
            return null;
        }
        AppUser user = appUserService.getById(cached.userId());
        if (user == null) {
            tokenCacheService.delete(token);
            return null;
        }
        user.setPassword(null);
        return user;
    }

    @Override
    public void deleteToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenCacheService.delete(token);
    }
}
