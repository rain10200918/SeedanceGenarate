package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.AuthResponse;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.mapper.AppUserMapper;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.InviteCodeService;
import org.example.seedancegenarate.service.UserActivityService;
import org.example.seedancegenarate.service.UserTokenService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AppUserServiceImpl extends ServiceImpl<AppUserMapper, AppUser> implements AppUserService {
    private final UserTokenService userTokenService;
    private final UserActivityService userActivityService;
    private final InviteCodeService inviteCodeService;
    private final org.example.seedancegenarate.mapper.WalletMapper walletMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public AuthResponse register(String username, String password, String inviteCode, HttpServletRequest request) {
        validateInviteCode(inviteCode);
        validateAuthParams(username, password);
        String normalizedUsername = username.trim();
        long count = this.count(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, normalizedUsername));
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setTotalCost(BigDecimal.ZERO);
        this.save(user);
        walletMapper.insertIgnore(user.getId());
        inviteCodeService.consume(inviteCode, user.getId());
        userActivityService.recordRegister(user, request);
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse login(String username, String password, HttpServletRequest request) {
        validateAuthParams(username, password);
        AppUser user = this.getOne(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username.trim()),
                false
        );
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        userActivityService.recordLogin(user, request);
        return buildAuthResponse(user);
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new RuntimeException("新密码至少 6 位");
        }
        AppUser user = this.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        AppUser update = new AppUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword.trim()));
        this.updateById(update);
    }

    private void validateInviteCode(String inviteCode) {
        if (!StringUtils.hasText(inviteCode)) {
            throw new RuntimeException("请输入邀请码");
        }
    }

    private void validateAuthParams(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new RuntimeException("用户名和密码不能为空");
        }
        if (username.trim().length() < 3) {
            throw new RuntimeException("用户名至少 3 个字符");
        }
        if (password.length() < 6) {
            throw new RuntimeException("密码至少 6 个字符");
        }
    }

    private AuthResponse buildAuthResponse(AppUser user) {
        String token = userTokenService.createToken(user.getId());
        user.setPassword(null);
        return new AuthResponse(token, user);
    }
}
