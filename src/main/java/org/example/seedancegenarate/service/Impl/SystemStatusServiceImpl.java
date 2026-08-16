package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.NodeHealth;
import org.example.seedancegenarate.dto.SystemStatus;
import org.example.seedancegenarate.entity.AsyncJob;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.example.seedancegenarate.mapper.VideoTaskMapper;
import org.example.seedancegenarate.service.NodeHealthService;
import org.example.seedancegenarate.service.SystemStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 系统状态实现：卡死任务（超时阈值）/ 生成中 / 成功率 / 死信作业 / 节点，
 * 与 MetricsExportTask 同源查询，管理端免 Prometheus 直看。
 */
@Service
@RequiredArgsConstructor
public class SystemStatusServiceImpl implements SystemStatusService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RATE_WINDOW_MINUTES = 5;
    private static final int STUCK_LIMIT = 20;

    private final VideoTaskMapper videoTaskMapper;
    private final AsyncJobMapper asyncJobMapper;
    private final NodeHealthService nodeHealthService;

    @Value("${video.task-timeout-minutes:60}")
    private long timeoutMinutes;

    @Override
    public SystemStatus current() {
        LocalDateTime now = LocalDateTime.now();

        // 生成中（按引擎）
        List<SystemStatus.ProviderCount> processing = videoTaskMapper.selectMaps(
                        Wrappers.<VideoTask>query()
                                .select("provider", "COUNT(*) AS cnt")
                                .eq("status", "PROCESSING")
                                .groupBy("provider"))
                .stream()
                .map(m -> new SystemStatus.ProviderCount(
                        String.valueOf(m.get("provider")),
                        ((Number) m.get("cnt")).longValue()))
                .toList();

        // 卡死任务（超过超时阈值仍 PROCESSING，含详情）
        LocalDateTime cutoff = now.minusMinutes(timeoutMinutes);
        List<SystemStatus.StuckTask> stuck = videoTaskMapper.selectList(Wrappers.<VideoTask>lambdaQuery()
                        .eq(VideoTask::getStatus, "PROCESSING")
                        .lt(VideoTask::getLastAttemptAt, cutoff)
                        .select(VideoTask::getId, VideoTask::getTaskId, VideoTask::getProvider,
                                VideoTask::getCreateTime, VideoTask::getLastAttemptAt)
                        .orderByAsc(VideoTask::getLastAttemptAt)
                        .last("limit " + STUCK_LIMIT))
                .stream()
                .map(t -> new SystemStatus.StuckTask(
                        t.businessTaskId(), t.getProvider(),
                        Math.max(java.time.Duration.between(t.getLastAttemptAt(), now).toMinutes(), 0)))
                .toList();

        // 死信作业
        long deadJobs = asyncJobMapper.selectCount(Wrappers.<AsyncJob>lambdaQuery()
                .eq(AsyncJob::getStatus, AsyncJob.STATUS_DEAD));

        // 近 5 分钟成功率
        LocalDateTime since = now.minusMinutes(RATE_WINDOW_MINUTES);
        long success = videoTaskMapper.selectCount(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "SUCCESS").ge(VideoTask::getUpdateTime, since));
        long failed = videoTaskMapper.selectCount(Wrappers.<VideoTask>lambdaQuery()
                .eq(VideoTask::getStatus, "FAILED").ge(VideoTask::getUpdateTime, since));
        Double rate = success + failed == 0 ? null
                : Math.round(success * 10000.0 / (success + failed)) / 100.0;

        // 节点状态（复用健康探测）
        List<SystemStatus.NodeStatus> nodes = nodeHealthService.checkAll().stream()
                .map(n -> new SystemStatus.NodeStatus(
                        n.id(), n.online(), n.queueLoad(), n.latencyMs(), n.error()))
                .toList();

        return new SystemStatus(processing, stuck, deadJobs,
                success, failed, rate, nodes, now.format(TIME_FMT));
    }
}
