package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.UserToken;

public interface UserTokenService extends IService<UserToken> {
    String createToken(Long userId);

    AppUser getUserByToken(String token);

    void deleteToken(String token);

    int deleteExpiredTokens();
}
