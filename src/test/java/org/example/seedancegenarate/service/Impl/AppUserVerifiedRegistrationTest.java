package org.example.seedancegenarate.service.Impl;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.exception.BusinessException;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.mapper.WalletMapper;
import org.example.seedancegenarate.service.InviteCodeService;
import org.example.seedancegenarate.service.RegistrationEmailSessionService;
import org.example.seedancegenarate.service.UserActivityService;
import org.example.seedancegenarate.service.UserTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppUserVerifiedRegistrationTest {

    @Test
    void verifiedEmailUserWalletAndInviteStayInsideOneTransactionalMethod() throws Exception {
        // 【测什么】唯一事务入口写验证邮箱/用户，再建钱包、消费邀请码；Redis token 不在此方法内创建。
        // 【怎么算红】移除 @Transactional、漏写验证时间、或把 token 签发搬回用户服务，本测试必须变红。
        Fixture fixture = fixture();
        when(fixture.users.selectCount(any())).thenReturn(0L, 0L);
        when(fixture.users.insert(any(AppUser.class))).thenAnswer(invocation -> {
            invocation.<AppUser>getArgument(0).setId(42L);
            return 1;
        });
        HttpServletRequest request = mock(HttpServletRequest.class);
        LocalDateTime before = LocalDateTime.now();

        AppUser result = fixture.service.registerVerified(verified(), "secret1", request);

        assertEquals(42L, result.getId());
        assertEquals("alice@example.com", result.getEmail());
        assertNotNull(result.getEmailVerifiedAt());
        assertTrue(!result.getEmailVerifiedAt().isBefore(before));
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("secret1", result.getPassword()));
        assertNotNull(AppUserServiceImpl.class.getMethod(
                "registerVerified",
                RegistrationEmailSessionService.VerifiedRegistration.class,
                String.class,
                HttpServletRequest.class
        ).getAnnotation(Transactional.class));
        var order = inOrder(fixture.users, fixture.wallets, fixture.invites, fixture.activity);
        order.verify(fixture.users).insert(any(AppUser.class));
        order.verify(fixture.wallets).insertIgnore(42L);
        order.verify(fixture.invites).consume("INVITE", 42L);
        order.verify(fixture.activity).recordRegister(result, request);
        verifyNoInteractions(fixture.tokens);
    }

    @Test
    void databaseUniqueRaceStopsBeforeWalletAndInviteWrites() {
        // 【测什么】count 预查后发生用户名/邮箱唯一键竞争仍转换 409，且不继续建钱包或消费邀请码。
        // 【怎么算红】只依赖 count 或吞掉 DuplicateKeyException，本测试会继续执行下游副作用。
        Fixture fixture = fixture();
        when(fixture.users.selectCount(any())).thenReturn(0L, 0L);
        when(fixture.users.insert(any(AppUser.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service.registerVerified(
                        verified(), "secret1", mock(HttpServletRequest.class)
                )
        );

        assertEquals(409, error.getCode());
        verify(fixture.wallets, never()).insertIgnore(any());
        verifyNoInteractions(fixture.invites, fixture.activity, fixture.tokens);
    }

    private static Fixture fixture() {
        AppUserMapper users = mock(AppUserMapper.class);
        WalletMapper wallets = mock(WalletMapper.class);
        InviteCodeService invites = mock(InviteCodeService.class);
        UserActivityService activity = mock(UserActivityService.class);
        UserTokenService tokens = mock(UserTokenService.class);
        AppUserServiceImpl service = new AppUserServiceImpl(tokens, activity, invites, wallets);
        ReflectionTestUtils.setField(service, "baseMapper", users);
        return new Fixture(service, users, wallets, invites, activity, tokens);
    }

    private static RegistrationEmailSessionService.VerifiedRegistration verified() {
        RegistrationEmailSessionService.VerifiedRegistration verified =
                mock(RegistrationEmailSessionService.VerifiedRegistration.class);
        when(verified.username()).thenReturn("alice");
        when(verified.email()).thenReturn("alice@example.com");
        when(verified.inviteCode()).thenReturn("INVITE");
        return verified;
    }

    private record Fixture(
            AppUserServiceImpl service,
            AppUserMapper users,
            WalletMapper wallets,
            InviteCodeService invites,
            UserActivityService activity,
            UserTokenService tokens
    ) {
    }
}
