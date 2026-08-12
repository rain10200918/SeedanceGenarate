package org.example.seedancegenarate.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.entity.AppUser;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「最后活跃」信息的写合并缓冲。
 * <p>
 * 原先每个 {@code /api/**} 请求都对 {@code app_user} 做一次 UPDATE（写 binlog、刷 redo、
 * 锁该用户自己那一行），而写的是最后活跃 IP / 最后操作这类丢几秒无所谓的观测数据。
 * 同一用户并发多个请求（刷列表 + SSE + 查状态）会在同一行上排队。
 * <p>
 * 这里按 userId 合并——同一用户在一个 flush 周期内只保留最后一条，
 * 定时批量落库。N 次请求 N 次 UPDATE 变成一个周期 ≤1 次。
 * <p>
 * 丢失语义：进程被 kill -9 时最多丢一个 flush 周期的「最后活跃」。
 * 登录 / 注册不走这里（{@code UserActivityServiceImpl} 直写），那两条要立刻可见。
 */
@Slf4j
@Component
public class UserActivityBuffer {

    /** userId → 最后一次活跃快照。同 key 覆盖即「合并」。 */
    private final Map<Long, Activity> pending = new ConcurrentHashMap<>();

    private final AppUserService appUserService;
    private final ConfigCacheProperties properties;

    /**
     * {@code @Lazy} 打断循环依赖：AppUserServiceImpl 依赖 UserActivityService，
     * 而本组件被 UserActivityServiceImpl 依赖 —— 与 UserActivityServiceImpl 自己
     * 对 AppUserService 用 @Lazy 是同一个理由。
     */
    public UserActivityBuffer(@Lazy AppUserService appUserService, ConfigCacheProperties properties) {
        this.appUserService = appUserService;
        this.properties = properties;
    }

    /**
     * 记一次活跃。开关关闭时立即落库（回退原行为）；否则进缓冲等批量。
     */
    public void record(Long userId, String ip, String location, String operation) {
        if (userId == null) {
            return;
        }
        Activity activity = new Activity(ip, location, operation, LocalDateTime.now());
        if (!properties.getActivity().isEnabled()) {
            write(userId, activity);
            return;
        }
        pending.put(userId, activity);
    }

    /**
     * 批量落库。逐用户一条 UPDATE——不合并成一条 SQL 是有意的：
     * 每个用户的字段值不同，拼 CASE WHEN 会让这段变成难读的字符串拼接，
     * 而这里的收益来自「次数从每请求一次降到每周期一次」，不来自单条 SQL 的形状。
     */
    @Scheduled(fixedDelayString = "${cache.activity.flush-interval-ms:5000}")
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        // 先取走再写：写库期间新来的活跃进入新的一批，不会被这一轮清掉
        List<Long> userIds = new ArrayList<>(pending.keySet());
        for (Long userId : userIds) {
            Activity activity = pending.remove(userId);
            if (activity != null) {
                write(userId, activity);
            }
        }
    }

    /** 停机时把缓冲里剩下的写完，别把最后一批带走。 */
    @PreDestroy
    public void flushOnShutdown() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("停机 flush 最后活跃信息失败: {}", e.getMessage());
        }
    }

    private void write(Long userId, Activity activity) {
        try {
            AppUser update = new AppUser();
            update.setId(userId);
            update.setLastActiveIp(activity.ip());
            update.setLastActiveIpLocation(activity.location());
            update.setLastOperation(activity.operation());
            update.setLastOperationTime(activity.at());
            appUserService.updateById(update);
        } catch (Exception e) {
            // 观测数据，写失败不影响业务；下一次活跃会再写一遍
            log.warn("写入最后活跃信息失败: userId={}, reason={}", userId, e.getMessage());
        }
    }

    /** 缓冲中待落库的用户数（供测试与排查用）。 */
    public int pendingCount() {
        return pending.size();
    }

    private record Activity(String ip, String location, String operation, LocalDateTime at) {
    }
}
