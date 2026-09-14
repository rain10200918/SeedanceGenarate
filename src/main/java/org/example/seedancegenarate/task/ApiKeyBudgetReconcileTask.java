package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.ApiKeyBudgetService;
import org.example.seedancegenarate.service.BillingAuthorizationService;
import org.example.seedancegenarate.service.VideoTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each instance may scan. Database authorization state and wallet ledger arbitrate every write. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyBudgetReconcileTask {
    private final ApiKeyBudgetService budgets;
    private final BillingAuthorizationService billing;
    private final VideoTaskService tasks;
    private long afterTaskId;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public synchronized void reconcile() {
        try {
            var batch = budgets.findReserved(afterTaskId, 100);
            if (batch.isEmpty()) {
                afterTaskId = 0;
                return;
            }
            for (var hold : batch) {
                // Advance even on poison rows, so one broken task cannot starve other refunds.
                afterTaskId = hold.taskId();
                try {
                    var task = tasks.getById(hold.taskId());
                    if (task == null) {
                        log.error("Key预算预占缺失任务，保留预占等待核查: taskId={}", hold.taskId());
                    } else if ("SUCCESS".equals(task.getStatus())) {
                        billing.settle(task, hold.amount());
                    } else if ("FAILED".equals(task.getStatus())) {
                        billing.release(task, hold.amount());
                    }
                    // PROCESSING / unknown provider acceptance never authorizes an automatic refund.
                } catch (Exception e) {
                    log.error("Key预算终态补偿失败，下一轮扫描重试: taskId={}", hold.taskId(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Key预算补偿查询失败，下轮重试", e);
        }
    }
}
