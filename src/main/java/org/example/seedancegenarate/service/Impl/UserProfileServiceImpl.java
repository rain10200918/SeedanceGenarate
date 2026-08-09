package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.ApiKeyUserView;
import org.example.seedancegenarate.dto.UserStatsResponse;
import org.example.seedancegenarate.engine.ModelSpec;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.UserProfileService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 个人中心实现。统计全部内存聚合现算（数据量小，避免自定义 SQL）：
 * 单次加载用户的 video_task 全量，分组算趋势/分布/明细——不建第二处真相。
 */
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppUserService appUserService;
    private final VideoTaskService videoTaskService;
    private final ApiKeyMapper apiKeyMapper;
    private final ApiCallLogMapper apiCallLogMapper;
    private final VideoEngineRegistry videoEngineRegistry;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public UserStatsResponse stats(Long userId) {
        AppUser user = appUserService.getById(userId);
        BigDecimal totalCost = user == null || user.getTotalCost() == null ? BigDecimal.ZERO : user.getTotalCost();

        List<VideoTask> tasks = videoTaskService.list(
                Wrappers.<VideoTask>lambdaQuery().eq(VideoTask::getUserId, userId));
        long taskTotal = tasks.size();
        long taskSuccess = tasks.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
        long taskFailed = tasks.stream().filter(t -> "FAILED".equals(t.getStatus())).count();
        double successRate = taskTotal == 0 ? 0 : Math.round(taskSuccess * 10000.0 / taskTotal) / 100.0;

        // 本月消费（按已记账金额汇总）
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        BigDecimal monthCost = tasks.stream()
                .filter(t -> t.getCreateTime() != null && !t.getCreateTime().isBefore(monthStart))
                .map(UserProfileServiceImpl::costOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 近 7 天趋势（补零）
        List<UserStatsResponse.DailyCount> dailyTrend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            long count = tasks.stream()
                    .filter(t -> t.getCreateTime() != null && t.getCreateTime().toLocalDate().equals(day))
                    .count();
            dailyTrend.add(new UserStatsResponse.DailyCount(day.format(DAY), count));
        }

        // 按模型分布（count + 金额）
        Map<String, String> labels = videoEngineRegistry.all().stream()
                .flatMap(engine -> engine.models().stream())
                .collect(Collectors.toMap(ModelSpec::model, ModelSpec::label, (a, b) -> a));
        List<UserStatsResponse.ModelStat> modelStats = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getModel() == null || t.getModel().isBlank() ? "未知" : t.getModel(),
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), group -> {
                            String model = group.get(0).getModel() == null ? "未知" : group.get(0).getModel();
                            long count = group.size();
                            BigDecimal cost = group.stream()
                                    .map(UserProfileServiceImpl::costOf)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return new UserStatsResponse.ModelStat(model, labels.getOrDefault(model, model), count, cost);
                        })))
                .values().stream()
                .sorted(Comparator.comparing(UserStatsResponse.ModelStat::count).reversed())
                .toList();

        // 最近记录（最近 10 条）
        List<UserStatsResponse.RecentTask> recentTasks = tasks.stream()
                .sorted(Comparator.comparing(VideoTask::getId).reversed())
                .limit(10)
                .map(t -> new UserStatsResponse.RecentTask(t.businessTaskId(), t.getModel(), t.getStatus(),
                        t.getCostAmount(), t.getCreateTime()))
                .toList();

        return new UserStatsResponse(totalCost, monthCost, taskTotal, taskSuccess, taskFailed,
                successRate, dailyTrend, modelStats, recentTasks);
    }

    @Override
    public List<ApiKeyUserView> apiKeys(Long userId) {
        List<ApiKey> keys = apiKeyMapper.selectList(
                Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getUserId, userId).orderByDesc(ApiKey::getId));
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ApiCallLog>> logsByKey = apiCallLogMapper.selectList(
                        Wrappers.<ApiCallLog>lambdaQuery().eq(ApiCallLog::getUserId, userId))
                .stream()
                .collect(Collectors.groupingBy(ApiCallLog::getApiKeyId));
        return keys.stream()
                .map(key -> {
                    List<ApiCallLog> logs = logsByKey.getOrDefault(key.getId(), List.of());
                    long callCount = logs.size();
                    long successCount = logs.stream().filter(l -> "SUCCESS".equals(l.getStatus())).count();
                    long failedCount = logs.stream().filter(l -> "FAILED".equals(l.getStatus())).count();
                    long rejectedCount = logs.stream().filter(l -> "REJECTED".equals(l.getStatus())).count();
                    BigDecimal totalCost = logs.stream()
                            .map(l -> l.getCostAmount() == null ? BigDecimal.ZERO : l.getCostAmount())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new ApiKeyUserView(key.getId(), key.getKeyPrefix(), key.getName(), key.getStatus(),
                            key.getLastUsedAt(), key.getCreateTime(),
                            callCount, successCount, failedCount, rejectedCount, totalCost);
                })
                .toList();
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            throw new RuntimeException("旧密码和新密码都不能为空");
        }
        if (newPassword.length() < 6) {
            throw new RuntimeException("新密码至少 6 位");
        }
        AppUser user = appUserService.getById(userId);
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }
        AppUser update = new AppUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        appUserService.updateById(update);
    }

    private static BigDecimal costOf(VideoTask task) {
        return task.getCostAmount() == null ? BigDecimal.ZERO : task.getCostAmount();
    }
}
