package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.dto.AdminDashboardResponse;
import org.example.seedancegenarate.dto.ApiCallLogView;
import org.example.seedancegenarate.dto.ApiCallSummary;
import org.example.seedancegenarate.dto.UserProfileDetail;
import org.example.seedancegenarate.dto.UserSummary;
import org.example.seedancegenarate.entity.ApiCallLog;
import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.AppUser;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.example.seedancegenarate.mapper.ApiKeyMapper;
import org.example.seedancegenarate.service.AdminStatsService;
import org.example.seedancegenarate.service.AppUserService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.example.seedancegenarate.util.ModelLabels;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 管理端统计实现。看板与汇总均内存聚合现算（不建计数器表，避免与明细漂移）。
 * <p>
 * 代价是这些方法很贵：{@code dashboard()} 把整张 {@code video_task} 读进内存聚合，
 * {@code api_call_log} 上是 {@code COUNT(*)} + {@code GROUP BY} 全表扫，而这两张表只增不减。
 * 后台看的数字不需要秒级精确，所以整个结果按短 TTL 缓存（{@code cache.stats.ttl-ms}）——
 * 多个管理员同时刷新、或一个人反复刷新，都只在 TTL 到期后真正算一次。
 * <p>
 * {@code apiCalls()} 分页明细不缓存：参数组合多、命中率低，且管理员翻页时期望看到实时数据。
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VideoTaskService videoTaskService;
    private final AppUserService appUserService;
    private final ApiCallLogMapper apiCallLogMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final VideoEngineRegistry videoEngineRegistry;
    private final ConfigCacheProperties cacheProperties;

    /** 聚合结果缓存：key → (算出来的值, 算的时刻)。同一 key 并发只是重复算，不会错。 */
    private final Map<String, Cached<?>> statsCache = new ConcurrentHashMap<>();

    @Override
    public AdminDashboardResponse dashboard() {
        return cached("dashboard", this::computeDashboard);
    }

    /**
     * 取缓存，过期或未启用则现算。
     * 不加锁：并发时最坏是几个请求各算一次（结果相同），比让请求互相等锁便宜。
     */
    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> compute) {
        if (!cacheProperties.getStats().isEnabled()) {
            return compute.get();
        }
        long ttlMs = cacheProperties.getStats().getTtlMs();
        Cached<?> hit = statsCache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < ttlMs) {
            return (T) hit.value();
        }
        T value = compute.get();
        statsCache.put(key, new Cached<>(value, System.currentTimeMillis()));
        return value;
    }

    private record Cached<T>(T value, long at) {
    }

    private AdminDashboardResponse computeDashboard() {
        List<VideoTask> tasks = videoTaskService.list();
        long totalTask = tasks.size();
        long totalSuccess = tasks.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
        long totalFailed = tasks.stream().filter(t -> "FAILED".equals(t.getStatus())).count();
        double successRate = totalTask == 0 ? 0 : Math.round(totalSuccess * 10000.0 / totalTask) / 100.0;

        LocalDate today = LocalDate.now();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        long todayTask = 0;
        long todaySuccess = 0;
        long todayFailed = 0;
        BigDecimal todayCost = BigDecimal.ZERO;
        BigDecimal monthCost = BigDecimal.ZERO;
        for (VideoTask t : tasks) {
            LocalDateTime created = t.getCreateTime();
            if (created == null) {
                continue;
            }
            if (created.toLocalDate().equals(today)) {
                todayTask++;
                if ("SUCCESS".equals(t.getStatus())) todaySuccess++;
                if ("FAILED".equals(t.getStatus())) todayFailed++;
                todayCost = todayCost.add(costOf(t));
            }
            if (!created.isBefore(monthStart)) {
                monthCost = monthCost.add(costOf(t));
            }
        }

        // 近 7 天趋势（补零）
        List<AdminDashboardResponse.DailyCount> dailyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            long count = tasks.stream()
                    .filter(t -> t.getCreateTime() != null && t.getCreateTime().toLocalDate().equals(day))
                    .count();
            dailyTrend.add(new AdminDashboardResponse.DailyCount(day.format(DAY), count));
        }

        // 按模型分布
        Map<String, String> labelMap = ModelLabels.of(videoEngineRegistry);
        List<AdminDashboardResponse.ModelStat> modelStats = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getModel() == null || t.getModel().isBlank() ? "未知" : t.getModel(),
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), group -> {
                            String model = group.get(0).getModel() == null ? "未知" : group.get(0).getModel();
                            long count = group.size();
                            BigDecimal cost = group.stream()
                                    .map(AdminStatsServiceImpl::costOf)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return new AdminDashboardResponse.ModelStat(
                                    model, labelMap.getOrDefault(model, model), count, cost);
                        })))
                .values().stream()
                .sorted(Comparator.comparing(AdminDashboardResponse.ModelStat::count).reversed())
                .toList();

        // 消费 TOP 用户（10）
        Map<Long, List<VideoTask>> byUser = tasks.stream()
                .filter(t -> t.getUserId() != null)
                .collect(Collectors.groupingBy(VideoTask::getUserId));
        Map<Long, BigDecimal> costByUser = byUser.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().stream().map(AdminStatsServiceImpl::costOf)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        Map<Long, String> usernames = costByUser.keySet().isEmpty() ? Map.of()
                : appUserService.listByIds(costByUser.keySet()).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getUsername, (a, b) -> a));
        List<AdminDashboardResponse.TopUser> topUsers = costByUser.entrySet().stream()
                .sorted(Map.Entry.<Long, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .map(e -> new AdminDashboardResponse.TopUser(
                        e.getKey(), usernames.getOrDefault(e.getKey(), String.valueOf(e.getKey())), e.getValue()))
                .toList();

        return new AdminDashboardResponse(totalTask, totalSuccess, totalFailed, successRate,
                todayTask, todaySuccess, todayFailed, todayCost, monthCost, dailyTrend, modelStats, topUsers);
    }

    @Override
    public Page<ApiCallLogView> apiCalls(long current, long size, Long apiKeyId, String model, String provider,
                                         String status, String errorCode) {
        Page<ApiCallLog> page = apiCallLogMapper.selectPage(
                new Page<>(Math.max(current, 1L), Math.min(Math.max(size, 1L), 100L)),
                Wrappers.<ApiCallLog>lambdaQuery()
                        .eq(apiKeyId != null, ApiCallLog::getApiKeyId, apiKeyId)
                        .eq(StringUtils.hasText(model), ApiCallLog::getModel, model)
                        .eq(StringUtils.hasText(provider), ApiCallLog::getProvider, provider)
                        .eq(StringUtils.hasText(status), ApiCallLog::getStatus, status)
                        .eq(StringUtils.hasText(errorCode), ApiCallLog::getErrorCode, errorCode)
                        .orderByDesc(ApiCallLog::getId));

        List<ApiCallLog> records = page.getRecords();
        if (records.isEmpty()) {
            return new Page<ApiCallLogView>(page.getCurrent(), page.getSize(), page.getTotal())
                    .setRecords(List.of());
        }
        // 补查钥匙前缀 + 属主用户名（批量，避免 N+1）
        Map<Long, ApiKey> keys = apiKeyMapper.selectBatchIds(
                        records.stream().map(ApiCallLog::getApiKeyId).distinct().toList())
                .stream().collect(Collectors.toMap(ApiKey::getId, Function.identity()));
        Map<Long, String> usernames = appUserService.listByIds(
                        records.stream().map(ApiCallLog::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getUsername, (a, b) -> a));
        Page<ApiCallLogView> viewPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        viewPage.setRecords(records.stream()
                .map(r -> {
                    ApiKey key = keys.get(r.getApiKeyId());
                    return new ApiCallLogView(
                            r.getId(), r.getRequestId(),
                            key == null ? null : key.getKeyPrefix(),
                            usernames.get(r.getUserId()),
                            r.getTaskId(), r.getEndpoint(), r.getModel(), r.getProvider(), r.getStatus(),
                            r.getHttpCode(), r.getErrorCode(), r.getErrorMsg(),
                            r.getCostAmount(), r.getImageCount(), r.getDuration(), r.getRatio(), r.getMegapixels(),
                            r.getUserAgent(), r.getQueuedMs(), r.getGenerateMs(), r.getTotalMs(),
                            r.getClientIp(), r.getCreateTime(), r.getUpdateTime());
                })
                .toList());
        return viewPage;
    }

    @Override
    public ApiCallSummary apiCallSummary(Long apiKeyId) {
        // 按钥匙分别缓存：不同 apiKeyId 是不同结果，不能共用一个 key
        return cached("apiCallSummary:" + apiKeyId, () -> computeApiCallSummary(apiKeyId));
    }

    private ApiCallSummary computeApiCallSummary(Long apiKeyId) {
        List<ApiCallLog> logs = apiCallLogMapper.selectList(
                Wrappers.<ApiCallLog>lambdaQuery().eq(apiKeyId != null, ApiCallLog::getApiKeyId, apiKeyId));
        long total = logs.size();
        long success = logs.stream().filter(l -> "SUCCESS".equals(l.getStatus())).count();
        long failed = logs.stream().filter(l -> "FAILED".equals(l.getStatus())).count();
        long rejected = logs.stream().filter(l -> "REJECTED".equals(l.getStatus())).count();
        List<ApiCallSummary.ErrorCodeCount> byErrorCode = logs.stream()
                .filter(l -> l.getErrorCode() != null && !l.getErrorCode().isBlank())
                .collect(Collectors.groupingBy(ApiCallLog::getErrorCode, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new ApiCallSummary.ErrorCodeCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ApiCallSummary.ErrorCodeCount::count).reversed())
                .toList();
        return new ApiCallSummary(total, success, failed, rejected, byErrorCode);
    }

    @Override
    public UserSummary userSummary() {
        return cached("userSummary", this::computeUserSummary);
    }

    private UserSummary computeUserSummary() {
        long total = appUserService.count();
        long adminCount = appUserService.count(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getRole, "ADMIN"));
        long todayNew = appUserService.count(Wrappers.<AppUser>lambdaQuery()
                .ge(AppUser::getCreateTime, LocalDate.now().atStartOfDay()));
        BigDecimal totalCost = sumCost(videoTaskService.listMaps(Wrappers.<VideoTask>query()
                .select("COALESCE(SUM(cost_amount), 0) AS total")));
        return new UserSummary(total, adminCount, total - adminCount, todayNew, totalCost);
    }

    @Override
    public UserProfileDetail userDetail(Long userId) {
        AppUser user = appUserService.getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 任务 / API 调用状态分布（各一条 GROUP BY）
        long[] task = statusCounts(videoTaskService.listMaps(Wrappers.<VideoTask>query()
                .select("status", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .groupBy("status")));
        long[] api = statusCounts(apiCallLogMapper.selectMaps(Wrappers.<ApiCallLog>query()
                .select("status", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .groupBy("status")));
        BigDecimal totalCost = sumCost(videoTaskService.listMaps(Wrappers.<VideoTask>query()
                .select("COALESCE(SUM(cost_amount), 0) AS total")
                .eq("user_id", userId)));
        BigDecimal monthCost = sumCost(videoTaskService.listMaps(Wrappers.<VideoTask>query()
                .select("COALESCE(SUM(cost_amount), 0) AS total")
                .eq("user_id", userId)
                .ge("create_time", LocalDate.now().withDayOfMonth(1).atStartOfDay())));
        List<VideoTask> recentTasks = videoTaskService.list(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getUserId, userId)
                .orderByDesc(VideoTask::getId)
                .last("limit 5"));
        List<ApiCallLog> recentApiCalls = apiCallLogMapper.selectList(Wrappers.<ApiCallLog>lambdaQuery()
                .eq(ApiCallLog::getUserId, userId)
                .orderByDesc(ApiCallLog::getId)
                .last("limit 5"));
        return new UserProfileDetail(userId, user.getUsername(),
                task[0], task[1], task[2], task[3],
                api[0], api[1], api[2], api[4],
                totalCost, monthCost, recentTasks, recentApiCalls);
    }

    /** 状态分布聚合：返回 {total, success, failed, processing, rejected} */
    private static long[] statusCounts(List<Map<String, Object>> rows) {
        long success = 0, failed = 0, processing = 0, rejected = 0, total = 0;
        for (Map<String, Object> row : rows) {
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            total += cnt;
            switch (String.valueOf(row.get("status"))) {
                case "SUCCESS" -> success = cnt;
                case "FAILED" -> failed = cnt;
                case "PROCESSING" -> processing = cnt;
                case "REJECTED" -> rejected = cnt;
                default -> {
                    // PENDING 等未来状态仅计入 total
                }
            }
        }
        return new long[]{total, success, failed, processing, rejected};
    }

    private static BigDecimal sumCost(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object value = rows.get(0).get("total");
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static BigDecimal costOf(VideoTask task) {
        return task.getCostAmount() == null ? BigDecimal.ZERO : task.getCostAmount();
    }
}
