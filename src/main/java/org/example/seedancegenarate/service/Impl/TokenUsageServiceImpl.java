package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.ConfigCacheProperties;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.dto.PromptTokenSummary;
import org.example.seedancegenarate.entity.PromptTokenUsage;
import org.example.seedancegenarate.mapper.PromptTokenUsageMapper;
import org.example.seedancegenarate.service.TokenUsageService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词优化 token 消耗。
 * <p>
 * 汇总走 Redis 缓存：聚合是 COUNT/SUM + GROUP BY 全表扫，多实例（分布式）下由
 * Redis 共享一份结果，各实例不再各自算；TTL 复用 {@code cache.stats.ttl-ms}，
 * 后台数字滞后 TTL 内可接受。Redis 异常时静默回退直查 MySQL，不影响页面。
 * 分页明细不缓存：参数组合多、命中率低，且管理员翻页期望看到实时数据（与 api_call_log 明细一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageServiceImpl implements TokenUsageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptTokenUsageMapper mapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RateLimitConfig rateLimitConfig;
    private final ConfigCacheProperties cacheProperties;

    @Override
    public void record(PromptTokenUsage usage) {
        mapper.insert(usage);
        // 数据变了，主动失效汇总缓存：管理员刷新即见最新，不用等 TTL 过期。
        // 失效失败不影响记录写入（下次读取会现算兜底）。
        try {
            redisTemplate.delete(summaryKey());
        } catch (Exception e) {
            log.debug("失效 token 消耗汇总缓存失败: {}", e.getMessage());
        }
    }

    @Override
    public Page<PromptTokenUsage> page(long current, long size, String scene, String llmModel, String status) {
        LambdaQueryWrapper<PromptTokenUsage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(scene != null && !scene.isBlank(), PromptTokenUsage::getScene, scene)
                .eq(llmModel != null && !llmModel.isBlank(), PromptTokenUsage::getLlmModel, llmModel)
                .eq(status != null && !status.isBlank(), PromptTokenUsage::getStatus, status)
                .orderByDesc(PromptTokenUsage::getId);
        return mapper.selectPage(new Page<>(current, size), wrapper);
    }

    @Override
    public PromptTokenSummary summary() {
        // 命中直接返回；Redis 不可用 / 未启用时现算，页面不受影响
        try {
            String cached = redisTemplate.opsForValue().get(summaryKey());
            if (cached != null) {
                return objectMapper.readValue(cached, PromptTokenSummary.class);
            }
        } catch (Exception e) {
            log.debug("读取 token 消耗汇总缓存失败: {}", e.getMessage());
        }

        PromptTokenSummary summary = computeSummary();

        try {
            redisTemplate.opsForValue().set(summaryKey(), objectMapper.writeValueAsString(summary),
                    Duration.ofMillis(cacheProperties.getStats().getTtlMs()));
        } catch (Exception e) {
            log.debug("写入 token 消耗汇总缓存失败: {}", e.getMessage());
        }
        return summary;
    }

    /** 汇总缓存 key：{env}:seedance:rate:token-usage:summary（与 ETA 同体系的环境前缀隔离） */
    private String summaryKey() {
        return rateLimitConfig.getRedisKeyPrefix() + ":token-usage:summary";
    }

    private PromptTokenSummary computeSummary() {
        String todayStart = LocalDate.now().atStartOfDay().format(DATE_FMT);

        Map<String, Object> today = mapper.sumSince(todayStart);
        Map<String, Object> total = mapper.sumSince("1970-01-01 00:00:00");
        long todayFailed = mapper.failedCallsSince(todayStart);
        long totalFailed = mapper.failedCallsSince("1970-01-01 00:00:00");

        List<PromptTokenSummary.SceneTokens> byScene = mapper.groupByScene().stream()
                .map(m -> new PromptTokenSummary.SceneTokens(
                        String.valueOf(m.get("scene")),
                        ((Number) m.get("calls")).longValue(),
                        ((Number) m.get("tokens")).longValue()))
                .toList();

        return new PromptTokenSummary(
                longOf(today, "calls") + todayFailed,
                longOf(today, "total_tokens"),
                todayFailed,
                longOf(total, "calls") + totalFailed,
                longOf(total, "total_tokens"),
                totalFailed,
                longOf(total, "input_tokens"),
                longOf(total, "output_tokens"),
                byScene,
                dailyTrend7());
    }

    /** 近 7 天成功消耗（含今天），缺天补 0 */
    private List<PromptTokenSummary.DayTokens> dailyTrend7() {
        LocalDate today = LocalDate.now();
        String since = today.minusDays(6).atStartOfDay().format(DATE_FMT);

        Map<String, Map<String, Object>> byDate = new HashMap<>();
        for (Map<String, Object> row : mapper.groupByDay(since)) {
            byDate.put(String.valueOf(row.get("date")), row);
        }

        List<PromptTokenSummary.DayTokens> trend = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            Map<String, Object> row = byDate.get(date);
            trend.add(new PromptTokenSummary.DayTokens(
                    date,
                    row == null ? 0 : ((Number) row.get("calls")).longValue(),
                    row == null ? 0 : ((Number) row.get("tokens")).longValue()));
        }
        return trend;
    }

    private long longOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }
}
