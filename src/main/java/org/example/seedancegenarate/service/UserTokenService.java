package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.AppUser;

public interface UserTokenService {
    String createToken(Long userId);

    AppUser getUserByToken(String token);

    void deleteToken(String token);
}
