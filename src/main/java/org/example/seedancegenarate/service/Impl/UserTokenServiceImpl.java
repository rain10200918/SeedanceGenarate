package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.UserToken;
import org.example.seedancegenarate.mapper.UserTokenMapper;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.UserTokenService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserTokenServiceImpl extends ServiceImpl<UserTokenMapper, UserToken> implements UserTokenService {
    private final AppUserService appUserService;

    public UserTokenServiceImpl(@Lazy AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @Override
    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        UserToken userToken = new UserToken();
        userToken.setUserId(userId);
        userToken.setToken(token);
        userToken.setExpireTime(LocalDateTime.now().plusDays(30));
        this.save(userToken);
        return token;
    }

    @Override
    public AppUser getUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        UserToken userToken = this.getOne(
                Wrappers.<UserToken>lambdaQuery()
                        .eq(UserToken::getToken, token)
                        .gt(UserToken::getExpireTime, LocalDateTime.now()),
                false
        );
        if (userToken == null) {
            return null;
        }
        AppUser user = appUserService.getById(userToken.getUserId());
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    @Override
    public void deleteToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        this.remove(Wrappers.<UserToken>lambdaQuery().eq(UserToken::getToken, token));
    }

    @Override
    public int deleteExpiredTokens() {
        return this.getBaseMapper().delete(
                Wrappers.<UserToken>lambdaQuery().le(UserToken::getExpireTime, LocalDateTime.now())
        );
    }
}
