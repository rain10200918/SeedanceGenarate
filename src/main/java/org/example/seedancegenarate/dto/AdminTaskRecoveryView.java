package org.example.seedancegenarate.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理员处置提交不确定任务所需的最小完整证据。 */
public record AdminTaskRecoveryView(
        Long id,
        String taskId,
        String status,
        String phase,
        String provider,
        BigDecimal freezeAmount,
        Long attemptId,
        String attemptStatus,
        String nodeId,
        String providerRequestId,
        String providerTaskId,
        String lastError,
        LocalDateTime attemptUpdatedAt,
        RecoveryJob recoveryJob
) {
    public record RecoveryJob(
            String status,
            Integer attempts,
            Integer maxAttempts,
            String lastError,
            LocalDateTime availableAt,
            LocalDateTime leaseUntil
    ) {
    }
}
