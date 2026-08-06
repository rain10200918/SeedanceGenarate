package org.example.seedancegenarate.event;

import java.math.BigDecimal;

/**
 * 任务状态发生（终态）变化时发布的领域事件。
 * <p>
 * 由 {@code VideoTaskServiceImpl.updateStatus} 在成功 / 失败落库后发出；SSE 推送层
 * （{@code TaskStreamManager}）在事务提交后监听，把 {@link Message} 推给对应用户的浏览器。
 * {@code userId} 仅用于服务端路由到该用户的连接，不下发前端。
 */
public record TaskStatusChangedEvent(Long userId, Message message) {

    /** 推给浏览器的增量载荷（前端按 taskId 就地更新对应任务）。 */
    public record Message(
            String taskId,
            String status,
            String videoUrl,
            String outputType,
            String errorMsg,
            BigDecimal costAmount
    ) {
    }
}
