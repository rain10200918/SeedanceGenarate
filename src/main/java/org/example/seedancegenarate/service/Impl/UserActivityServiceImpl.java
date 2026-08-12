package org.example.seedancegenarate.service.Impl;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.UserActivityBuffer;
import org.example.seedancegenarate.service.UserActivityService;
import org.example.seedancegenarate.util.IpUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserActivityServiceImpl implements UserActivityService {
    private final AppUserService appUserService;
    private final UserActivityBuffer userActivityBuffer;

    public UserActivityServiceImpl(@Lazy AppUserService appUserService,
                                   UserActivityBuffer userActivityBuffer) {
        this.appUserService = appUserService;
        this.userActivityBuffer = userActivityBuffer;
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

    /**
     * 每个 {@code /api/**} 请求都会走这里，所以不直写库——交给 {@link UserActivityBuffer}
     * 按 userId 合并后定时批量落库（原先每请求一次 UPDATE，且并发时抢同一行的行锁）。
     * 登录 / 注册仍直写：那两条低频且要立刻可见。
     */
    @Override
    public void recordOperation(Long userId, String operation, HttpServletRequest request) {
        if (userId == null || request == null) {
            return;
        }
        String ip = IpUtils.getClientIp(request);
        String location = IpUtils.getIpLocation(request, ip);
        userActivityBuffer.record(userId, ip, location, operation);
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
