package org.example.seedancegenarate.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.entity.Pipeline;
import org.example.seedancegenarate.mapper.PipelineMapper;
import org.example.seedancegenarate.service.DistributedLock;
import org.example.seedancegenarate.service.PipelineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 流水线对账（替代旧的启动全量恢复）：
 * <ul>
 *   <li>节点全终态但流水线仍 RUNNING → 汇总为 DONE / PARTIAL_FAILED；</li>
 *   <li>PENDING 节点缺少活跃提交作业（实例重启丢过内存循环）→ 补插作业让 Worker 接管。</li>
 * </ul>
 * 分布式锁保证多实例下同一时刻只有一台执行对账。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineReconcileTask {
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);

    private final PipelineMapper pipelineMapper;
    private final PipelineService pipelineService;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    @Value("${pipeline.job-driven:true}")
    private boolean jobDriven;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void reconcileRunningPipelines() {
        if (!jobDriven) {
            return;
        }
        if (!lockProperties.isEnabled()) {
            // 单实例开发：未启用锁，直接执行（兼容旧行为）
            reconcileLocked();
            return;
        }
        AutoCloseable lock = distributedLock.tryLock("pipeline-reconcile", LOCK_TTL);
        if (lock == null) {
            return; // 其他实例正在对账，或 Redis 不可用（fail-closed）
        }
        try (lock) {
            reconcileLocked();
        } catch (Exception e) {
            log.warn("流水线对账失败: {}", e.getMessage());
        }
    }

    private void reconcileLocked() {
        List<Pipeline> running = pipelineMapper.selectList(Wrappers.<Pipeline>lambdaQuery()
                .eq(Pipeline::getStatus, "RUNNING"));
        for (Pipeline pipeline : running) {
            pipelineService.reconcileRunning(pipeline.getId());
        }
    }
}
