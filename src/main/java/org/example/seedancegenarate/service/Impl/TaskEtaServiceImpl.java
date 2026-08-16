package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.RateLimitConfig;
import org.example.seedancegenarate.engine.EtaCapability;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.TaskEtaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ETA 估算（分布式安全）：
 * <ul>
 *   <li>平均耗时：按 model 聚合近 7 天成功任务（创建→成功全程），Redis 缓存 5 分钟，
 *       所有实例共享同一份统计；任务完成时主动刷新；</li>
 *   <li>队列位置：FULL 引擎查提供方真实队列（ComfyUI /queue），Redis 缓存 5 秒防热点；</li>
 *   <li>无本地状态 → 任意实例可服务，轮询落到不同实例结果一致。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEtaServiceImpl implements TaskEtaService {
    private static final Duration AVG_TTL = Duration.ofMinutes(5);
    private static final Duration QUEUE_TTL = Duration.ofSeconds(5);
    private static final long AVG_MIN_SAMPLES = 3;
    private static final long AVG_WINDOW_DAYS = 7;

    // 直接用 Mapper 而非 VideoTaskService：避免与 VideoTaskServiceImpl 循环依赖
    private final VideoTaskMapper videoTaskMapper;
    private final VideoEngineRegistry videoEngineRegistry;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RateLimitConfig rateLimitConfig;

    /** 样本时长上限（分钟）：对账补终态的历史卡死任务（引擎完成早、终态晚）时长虚大，
     *  不能代表真实生成耗时，超过上限的样本剔除。 */
    @Value("${video.eta-max-sample-minutes:90}")
    private long maxSampleMinutes;

    @Override
    public TaskEta estimate(VideoTask task) {
        String stage = stageOf(task);
        long elapsedSeconds = elapsedSeconds(task);
        switch (stage) {
            case "DONE" -> {
                return new TaskEta(task.businessTaskId(), stage, null, 0L, elapsedSeconds, 100);
            }
            case "FAILED" -> {
                return new TaskEta(task.businessTaskId(), stage, null, null, elapsedSeconds, null);
            }
            default -> {
                // QUEUED / RUNNING / UNKNOWN 继续估算
            }
        }

        Long avgSeconds = avgDurationSeconds(task);
        if (avgSeconds == null) {
            return new TaskEta(task.businessTaskId(), "UNKNOWN", null, null, elapsedSeconds, null);
        }

        VideoEngine engine = videoEngineRegistry.get(task.getProvider());
        if (engine.etaCapability() == EtaCapability.FULL) {
            Integer position = queuePositionCached(task, engine);
            if (position != null && position >= 0) {
                // 排队中：前面任务数 × 平均耗时
                long remaining = Math.max((position + 1L) * avgSeconds - elapsedSeconds, 1);
                int progress = (int) Math.min(100, elapsedSeconds * 100.0 / ((position + 1L) * avgSeconds));
                return new TaskEta(task.businessTaskId(), "QUEUED", position, remaining, elapsedSeconds, progress);
            }
        }

        // 生成中（或队列不可查）：平均耗时 - 已运行
        long remaining = Math.max(avgSeconds - elapsedSeconds, 0);
        int progress = (int) Math.min(100, elapsedSeconds * 100.0 / avgSeconds);
        return new TaskEta(task.businessTaskId(), "RUNNING", null, remaining, elapsedSeconds, progress);
    }

    @Override
    public void refreshAvgDuration(String model) {
        if (!StringUtils.hasText(model)) {
            return;
        }
        try {
            Long avg = computeAvgDurationSeconds(model, null);
            if (avg != null) {
                redisTemplate.opsForValue().set(avgKey(model), String.valueOf(avg), AVG_TTL);
            }
        } catch (Exception e) {
            log.warn("刷新 ETA 平均耗时缓存失败: model={}, reason={}", model, e.getMessage());
        }
    }

    private String stageOf(VideoTask task) {
        String status = task.getStatus();
        if ("SUCCESS".equals(status)) return "DONE";
        if ("FAILED".equals(status)) return "FAILED";
        return "PROCESSING";
    }

    private long elapsedSeconds(VideoTask task) {
        LocalDateTime start = task.getCreateTime();
        if (start == null) {
            return 0;
        }
        return Math.max(Duration.between(start, LocalDateTime.now()).getSeconds(), 0);
    }

    /** 平均耗时：Redis 缓存优先，miss 时 MySQL 聚合并写回；model 不足回退 provider 级。 */
    private Long avgDurationSeconds(VideoTask task) {
        String model = task.getModel();
        if (!StringUtils.hasText(model)) {
            return null;
        }
        try {
            String cached = redisTemplate.opsForValue().get(avgKey(model));
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.debug("读取 ETA 平均耗时缓存失败: {}", e.getMessage());
        }
        Long avg = computeAvgDurationSeconds(model, null);
        if (avg == null && StringUtils.hasText(task.getProvider())) {
            // model 样本不足：回退到该 provider 全体成功任务的平均（粗估）
            avg = computeAvgDurationSeconds(null, task.getProvider());
        }
        if (avg != null) {
            try {
                redisTemplate.opsForValue().set(avgKey(model), String.valueOf(avg), AVG_TTL);
            } catch (Exception e) {
                log.debug("写入 ETA 平均耗时缓存失败: {}", e.getMessage());
            }
        }
        return avg;
    }

    /** 近 7 天成功任务的创建→成功平均秒数；model 或 provider 二选一，样本不足返回 null。 */
    private Long computeAvgDurationSeconds(String model, String provider) {
        List<VideoTask> samples;
        try {
            var wrapper = Wrappers.<VideoTask>lambdaQuery()
                    .eq(VideoTask::getStatus, "SUCCESS")
                    .ge(VideoTask::getUpdateTime, LocalDateTime.now().minusDays(AVG_WINDOW_DAYS))
                    .select(VideoTask::getId, VideoTask::getCreateTime, VideoTask::getUpdateTime)
                    .last("limit 200");
            if (StringUtils.hasText(model)) {
                wrapper.eq(VideoTask::getModel, model);
            }
            if (StringUtils.hasText(provider)) {
                wrapper.eq(VideoTask::getProvider, provider);
            }
            samples = videoTaskMapper.selectList(wrapper);
        } catch (Exception e) {
            log.warn("聚合 ETA 平均耗时失败: model={}, provider={}, reason={}",
                    model, provider, e.getMessage());
            return null;
        }
        if (samples.size() < AVG_MIN_SAMPLES) {
            return null;
        }
        long totalSeconds = 0;
        int counted = 0;
        for (VideoTask t : samples) {
            if (t.getCreateTime() == null || t.getUpdateTime() == null) {
                continue;
            }
            long seconds = Duration.between(t.getCreateTime(), t.getUpdateTime()).getSeconds();
            // 上限过滤：历史卡死补终态的任务时长 12~15 天，混入会把平均耗时拉爆
            long maxSeconds = Math.max(maxSampleMinutes, 1) * 60;
            if (seconds > 0 && seconds < maxSeconds) {
                totalSeconds += seconds;
                counted++;
            }
        }
        return counted >= AVG_MIN_SAMPLES ? totalSeconds / counted : null;
    }

    /** 队列位置：Redis 缓存 5 秒防 /queue 热点；查询失败降级 null（按 BASIC 处理）。 */
    private Integer queuePositionCached(VideoTask task, VideoEngine engine) {
        String cacheKey = rateLimitConfig.getRedisKeyPrefix() + ":eta:queue:" + task.getId();
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return "null".equals(cached) ? null : Integer.parseInt(cached);
            }
        } catch (Exception e) {
            log.debug("读取 ETA 队列位置缓存失败: {}", e.getMessage());
        }
        try {
            Integer position = engine.queuePosition(task);
            try {
                redisTemplate.opsForValue().set(cacheKey,
                        position == null ? "null" : String.valueOf(position), QUEUE_TTL);
            } catch (Exception e) {
                log.debug("写入 ETA 队列位置缓存失败: {}", e.getMessage());
            }
            return position;
        } catch (Exception e) {
            log.debug("查询提供方队列位置失败: taskId={}, reason={}", task.getId(), e.getMessage());
            return null;
        }
    }

    /** 复用环境前缀保证隔离；key 形态：{env}:seedance:rate:eta:avg:{model} */
    private String avgKey(String model) {
        return rateLimitConfig.getRedisKeyPrefix() + ":eta:avg:" + model;
    }
}
