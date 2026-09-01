package org.example.seedancegenarate.service.Impl;

import org.example.seedancegenarate.config.AuthTokenProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
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
import static org.mockito.Mockito.never;
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
        // 【测什么】token Redis 写入失败使用可恢复的 503 业务语义，不伪装成注册事务失败。
        // 【怎么算红】恢复裸 IllegalStateException 或吞掉 put=false 返回 token，本测试必须变红。
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.put(anyString(), eq(7L), any(), eq(3600L))).thenReturn(false);
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        BusinessException error = assertThrows(BusinessException.class, () -> service.createToken(7L));

        assertEquals(503, error.getCode());
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
        // 【测什么】登出只把当前 token 交给 Redis 删除，不读用户资料。
        // 【怎么算红】把 deleteToken 改回先查 appUserService 再删除，这条必须变红。
        AppUserService users = mock(AppUserService.class);
        TokenCacheService cache = mock(TokenCacheService.class);
        when(cache.delete("token")).thenReturn(true);
        UserTokenServiceImpl service = new UserTokenServiceImpl(users, cache, properties());

        service.deleteToken("token");

        verify(cache).delete("token");
        verify(users, never()).getById(any());
    }

    @Test
    void blankLogoutIsIdempotentWithoutTouchingRedis() {
        // 【测什么】空串、纯空白 token 都是幂等登出，不访问 Redis。
        // 【怎么算红】删掉 deleteToken 的 isBlank 边界判断，这条必须变红。
        TokenCacheService cache = mock(TokenCacheService.class);
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        service.deleteToken(null);
        service.deleteToken("");
        service.deleteToken("   ");

        verify(cache, never()).delete(any());
    }

    @Test
    void logoutReportsServiceUnavailableWhenRedisDeleteFails() {
        // 【测什么】Redis DEL 异常不能被包装成登出成功，必须显式返回 503 语义。
        // 【怎么算红】恢复 RedisTokenCacheService.delete 吞掉异常的行为，这条必须变红。
        TokenCacheService cache = new RedisTokenCacheService(
                new FailingDeleteRedisTemplate(), properties());
        UserTokenServiceImpl service = new UserTokenServiceImpl(mock(AppUserService.class), cache, properties());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.deleteToken("token")
        );

        assertEquals(503, error.getCode());
    }

    private AuthTokenProperties properties() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setTtlSeconds(3600);
        properties.setRefreshThresholdSeconds(300);
        return properties;
    }

    private static final class FailingDeleteRedisTemplate
            extends org.springframework.data.redis.core.StringRedisTemplate {
        @Override
        public Boolean delete(String key) {
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis unavailable");
        }
    }
}
