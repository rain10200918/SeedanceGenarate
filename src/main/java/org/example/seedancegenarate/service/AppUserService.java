package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.dto.AuthResponse;
import org.example.seedancegenarate.entity.AppUser;

public interface AppUserService extends IService<AppUser> {
    AuthResponse register(String username, String password, String inviteCode, HttpServletRequest request);

    AuthResponse login(String username, String password, HttpServletRequest request);

    /** 管理员重置用户密码（BCrypt 加密后落库） */
    void resetPassword(Long userId, String newPassword);
}
