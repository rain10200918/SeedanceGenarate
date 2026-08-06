package org.example.seedancegenarate.service;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.entity.AppUser;

public interface UserActivityService {
    void recordRegister(AppUser user, HttpServletRequest request);

    void recordLogin(AppUser user, HttpServletRequest request);

    void recordOperation(Long userId, String operation, HttpServletRequest request);
}
