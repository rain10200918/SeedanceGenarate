package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.NodeHealthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标导出（Prometheus /actuator/prometheus 的补充）：
 * 30s 定时从 DB 现查（数据准、重启不丢），写入内存 holder，scrape 时读内存不查库。
 * <p>
 * 分布式：多实例导出相同值（Gauge 是全局事实），Prometheus 按相同 label 合并，无需锁。
 * <p>
 * 与告警规则配套（compose/rules.yml）：卡死任务 / 作业死信 / 节点掉线 / 成功率下降。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsExportTask {

    /** 近 5 分钟成功/失败（成功率窗口） */
    private static final int RATE_WINDOW_MINUTES = 5;

    private final VideoTaskMapper videoTaskMapper;
    private final AsyncJobMapper asyncJobMapper;
    private final NodeHealthService nodeHealthService;
    private final MeterRegistry meterRegistry;

    @Value("${video.task-timeout-minutes:60}")
    private long timeoutMinutes;

    /** Gauge holder：key=name+tag 值 → 定时刷新值 */
    private final ConcurrentMap<String, AtomicLong> holders = new ConcurrentHashMap<>();
    /** 已注册的 Gauge id（避免重复注册造成指标膨胀） */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void export() {
        try {
            exportTaskCounts();
        } catch (Exception e) {
            log.warn("导出任务指标失败: {}", e.getMessage());
        }
        try {
            exportJobDead();
        } catch (Exception e) {
            log.warn("导出作业指标失败: {}", e.getMessage());
        }
        try {
            exportNodeHealth();
        } catch (Exception e) {
            log.warn("导出节点指标失败: {}", e.getMessage());
        }
    }

    /** 生成中任务数（按引擎）+ 卡死数（超过超时阈值仍 PROCESSING）+ 近 5 分钟成功/失败 */
    private void exportTaskCounts() {
        List<Map<String, Object>> byProvider = videoTaskMapper.selectMaps(
                Wrappers.<VideoTask>query()
                        .select("provider", "COUNT(*) AS cnt")
                        .eq("status", "PROCESSING")
                        .groupBy("provider"));
        for (Map<String, Object> row : byProvider) {
            String provider = String.valueOf(row.get("provider"));
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            setGauge("task_processing_count", new String[]{"provider"}, new String[]{provider}, cnt);
        }

        // 卡死数：超过超时阈值仍 PROCESSING（决策每 30s 处理，正常应接近 0）
        long stuck = videoTaskMapper.selectCount(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "PROCESSING")
                .lt(VideoTask::getLastAttemptAt, LocalDateTime.now().minusMinutes(timeoutMinutes)));
        setGauge("task_stuck_count", new String[0], new String[0], stuck);

        // 近 5 分钟成功/失败（成功率窗口）
        LocalDateTime since = LocalDateTime.now().minusMinutes(RATE_WINDOW_MINUTES);
        long success = videoTaskMapper.selectCount(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "SUCCESS")
                .ge(VideoTask::getUpdateTime, since));
        long failed = videoTaskMapper.selectCount(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "FAILED")
                .ge(VideoTask::getUpdateTime, since));
        setGauge("task_success_total", new String[0], new String[0], success);
        setGauge("task_failed_total", new String[0], new String[0], failed);
    }

    /** 作业死信数（重试耗尽的作业，需要人工介入） */
    private void exportJobDead() {
        long dead = asyncJobMapper.selectCount(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getStatus, AsyncJob.STATUS_DEAD));
        setGauge("async_job_dead_count", new String[0], new String[0], dead);
    }

    /** 节点在线状态与队列负载（复用健康探测） */
    private void exportNodeHealth() {
        List<NodeHealth> nodes = nodeHealthService.checkAll();
        for (NodeHealth node : nodes) {
            setGauge("node_up", new String[]{"node_id"}, new String[]{node.id()}, node.online() ? 1 : 0);
            setGauge("node_queue_load", new String[]{"node_id"}, new String[]{node.id()}, node.queueLoad());
        }
    }

    /** 写 holder + 首次注册 Gauge（同名同 tag 复用，避免指标膨胀） */
    private void setGauge(String name, String[] tagKeys, String[] tagValues, double value) {
        String holderKey = name + Arrays.toString(tagValues);
        AtomicLong holder = holders.computeIfAbsent(holderKey, k -> new AtomicLong(0));
        holder.set((long) value);
        if (registered.add(holderKey)) {
            // tagKeys/tagValues 长度一致，拍平成 k1,v1,k2,v2... 交给 Tags.of(String...)
            String[] flat = new String[tagKeys.length * 2];
            for (int i = 0; i < tagKeys.length; i++) {
                flat[i * 2] = tagKeys[i];
                flat[i * 2 + 1] = tagValues[i];
            }
            meterRegistry.gauge(name, Tags.of(flat), holder, AtomicLong::get);
        }
    }
}
