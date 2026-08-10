package org.example.seedancegenarate.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.DistributedFeatureProperties;
import org.example.seedancegenarate.event.TaskStatusChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 在本地 SSE 与 Redis Pub/Sub 之间做单一事件出口，避免 Redis 开启时重复推送。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStatusEventBridge {
    private final DistributedFeatureProperties features;
    private final TaskStatusEventPublisher publisher;
    private final TaskStreamManager taskStreamManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusChanged(TaskStatusChangedEvent event) {
        if (features.isRedisTaskEvents()) {
            // 所有实例（包括发布实例）统一从 Redis 订阅后推送，保证每条连接只走一条路径。
            if (!publisher.publish(event)) {
                // Pub/Sub 只是通知通道；Redis 暂时不可用时至少保留当前实例的本地实时通知。
                taskStreamManager.pushLocal(event.userId(), event.message());
            }
            return;
        }
        taskStreamManager.pushLocal(event.userId(), event.message());
    }
}
