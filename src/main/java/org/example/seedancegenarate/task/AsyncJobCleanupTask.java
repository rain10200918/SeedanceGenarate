package org.example.seedancegenarate.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.mapper.AsyncJobMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 内部异步作业定期清理任务：
 * 仅清理 14 天前已终态（SUCCEEDED/DEAD）的历史临时作业，防止内部消息队列表无上限膨胀。
 * （注：业务数据如 api_call_log 保持永久保存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncJobCleanupTask {

    private final AsyncJobMapper asyncJobMapper;

    @Value("${async-job.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${async-job.cleanup.retention-days:14}")
    private int retentionDays;

    private static final int BATCH_SIZE = 1000;

    @Scheduled(cron = "${async-job.cleanup.cron:0 30 3 * * *}")
    public void cleanupExpiredAsyncJobs() {
        if (!cleanupEnabled) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int deleted;
        do {
            try {
                deleted = asyncJobMapper.deleteExpiredJobs(cutoff, BATCH_SIZE);
                totalDeleted += deleted;
            } catch (Exception e) {
                log.warn("清理历史 async_job 异常: {}", e.getMessage());
                break;
            }
        } while (deleted == BATCH_SIZE);

        if (totalDeleted > 0) {
            log.info("已清理 {} 条超过 {} 天的终态 async_job", totalDeleted, retentionDays);
        }
    }
}
