package org.example.seedancegenarate.stream;

import org.example.seedancegenarate.event.TaskStatusChangedEvent;

/** Redis Pub/Sub 中传输的轻量任务状态消息。 */
public record TaskStatusRedisMessage(
        int schemaVersion,
        String eventId,
        Long userId,
        TaskStatusChangedEvent.Message message
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
