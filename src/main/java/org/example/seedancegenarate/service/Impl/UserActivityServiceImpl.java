package org.example.seedancegenarate.service.Impl;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.UserActivityService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserActivityServiceImpl implements UserActivityService {
    private final AppUserService appUserService;

    public UserActivityServiceImpl(@Lazy AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @Override
    public void recordRegister(AppUser user, HttpServletRequest request) {
        if (user == null || user.getId() == null || request == null) {
            return;
        }
        String ip = IpUtils.getClientIp(request);
        String location = IpUtils.getIpLocation(request, ip);
        AppUser update = new AppUser();
        update.setId(user.getId());
        update.setRegisterIp(ip);
        update.setRegisterIpLocation(location);
        update.setLastLoginIp(ip);
        update.setLastLoginIpLocation(location);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastActiveIp(ip);
        update.setLastActiveIpLocation(location);
        update.setLastOperation("注册并登录");
        update.setLastOperationTime(LocalDateTime.now());
        appUserService.updateById(update);
        copyActivity(user, update);
    }

    @Override
    public void recordLogin(AppUser user, HttpServletRequest request) {
        if (user == null || user.getId() == null || request == null) {
            return;
        }
        String ip = IpUtils.getClientIp(request);
        String location = IpUtils.getIpLocation(request, ip);
        AppUser update = new AppUser();
        update.setId(user.getId());
        update.setLastLoginIp(ip);
        update.setLastLoginIpLocation(location);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastActiveIp(ip);
        update.setLastActiveIpLocation(location);
        update.setLastOperation("登录");
        update.setLastOperationTime(LocalDateTime.now());
        appUserService.updateById(update);
        copyActivity(user, update);
    }

    @Override
    public void recordOperation(Long userId, String operation, HttpServletRequest request) {
        if (userId == null || request == null) {
            return;
        }
        String ip = IpUtils.getClientIp(request);
        String location = IpUtils.getIpLocation(request, ip);
        AppUser update = new AppUser();
        update.setId(userId);
        update.setLastActiveIp(ip);
        update.setLastActiveIpLocation(location);
        update.setLastOperation(operation);
        update.setLastOperationTime(LocalDateTime.now());
        appUserService.updateById(update);
    }

    private void copyActivity(AppUser user, AppUser source) {
        user.setRegisterIp(source.getRegisterIp());
        user.setRegisterIpLocation(source.getRegisterIpLocation());
        user.setLastLoginIp(source.getLastLoginIp());
        user.setLastLoginIpLocation(source.getLastLoginIpLocation());
        user.setLastLoginTime(source.getLastLoginTime());
        user.setLastActiveIp(source.getLastActiveIp());
        user.setLastActiveIpLocation(source.getLastActiveIpLocation());
        user.setLastOperation(source.getLastOperation());
        user.setLastOperationTime(source.getLastOperationTime());
    }
}
