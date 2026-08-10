package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.TokenCacheService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserTokenServiceImplTest {

    @Test
    void createsTokenInRedis() {
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.put(anyString(), eq(7L), any(), eq(3600L))).thenReturn(true);
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        String token = service.createToken(7L);

        assertEquals(32, token.length());
        verify(cache).put(eq(token), eq(7L), any(Instant.class), eq(3600L));
    }

    @Test
    void rejectsLoginWhenRedisWriteFails() {
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.put(anyString(), eq(7L), any(), eq(3600L))).thenReturn(false);
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        assertThrows(IllegalStateException.class, () -> service.createToken(7L));
    }

    @Test
    void validationUsesFiveMinuteRefreshThreshold() {
        AppUserService users = mock(AppUserService.class);
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.getAndRefreshIfNeeded("token", 3600, 300)).thenReturn(
                new TokenCacheService.CachedToken(7L, Instant.now().plusSeconds(3600)));
        AppUser user = new AppUser();
        user.setId(7L);
        user.setPassword("secret");
        when(users.getById(7L)).thenReturn(user);
        UserTokenServiceImpl service = new UserTokenServiceImpl(users, cache, properties());

        AppUser resolved = service.getUserByToken("token");

        assertEquals(7L, resolved.getId());
        assertNull(resolved.getPassword());
        verify(cache).getAndRefreshIfNeeded("token", 3600, 300);
    }

    @Test
    void logoutDeletesRedisToken() {
        TokenCacheService cache = mock(TokenCacheService.class);
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        service.deleteToken("token");

        verify(cache).delete("token");
    }

    private AuthTokenProperties properties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setTtlSeconds(3600);
        properties.setRefreshThresholdSeconds(300);
        return properties;
    }
}
