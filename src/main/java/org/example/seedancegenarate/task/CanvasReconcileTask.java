package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedLockProperties;
import org.example.seedancegenarate.mapper.CanvasNodeMapper;
import org.example.seedancegenarate.service.CanvasRunService;
import org.example.seedancegenarate.service.DistributedLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 画布对账（与 {@link PipelineReconcileTask} 同一套路）：
 * <ul>
 *   <li>任务已终态但节点仍 PROCESSING → 补回填（提交是「先建任务、再写 taskId」两步，
 *       中间任务就可能已经跑完，那一次终态事件按 task_id 反查不到节点，白发了）；</li>
 *   <li>PENDING 节点缺少活跃提交作业（实例重启丢过在途作业）→ 补插作业让 Worker 接管；</li>
 *   <li>节点全终态但画布仍 RUNNING → 汇总为 DONE / PARTIAL_FAILED。</li>
 * </ul>
 * 分布式锁保证多实例下同一时刻只有一台执行对账。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasReconcileTask {
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);

    private final CanvasNodeMapper canvasNodeMapper;
    private final CanvasRunService canvasRunService;
    private final DistributedLock distributedLock;
    private final DistributedLockProperties lockProperties;

    /** 运维开关（对齐 pipeline.job-driven）：排障时可以关掉对账，不必改代码 */
    @Value("${canvas.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void reconcileRunningCanvases() {
        if (!reconcileEnabled) {
            return;
        }
        if (!lockProperties.isEnabled()) {
            // 单实例开发：未启用锁，直接执行
            reconcileLocked();
            return;
        }
        AutoCloseable lock = distributedLock.tryLock("canvas-reconcile", LOCK_TTL);
        if (lock == null) {
            return; // 其他实例正在对账，或 Redis 不可用（fail-closed）
        }
        try (lock) {
            reconcileLocked();
        } catch (Exception e) {
            log.warn("画布对账失败: {}", e.getMessage());
        }
    }

    private void reconcileLocked() {
        for (Long canvasId : canvasNodeMapper.selectCanvasIdsWithActiveNodes()) {
            try {
                canvasRunService.reconcileRunning(canvasId);
            } catch (Exception e) {
                // 一块画布对不平不能拖垮其余画布
                log.warn("画布对账失败 canvasId={}: {}", canvasId, e.getMessage());
            }
        }
    }
}
