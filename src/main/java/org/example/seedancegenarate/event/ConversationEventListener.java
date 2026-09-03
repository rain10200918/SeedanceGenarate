package org.example.seedancegenarate.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.ConversationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 对话里生成卡片的终态回填：监听既有 {@link TaskStatusChangedEvent}（与 SSE、画布同源），
 * 按 taskId 反查 conversation_message 写状态与产物——刷新页面不丢，后端是唯一事实源。
 * <p>
 * 同步执行于任务状态更新事务内，异常必须吞掉（不能因为对话回填失败而回滚任务更新）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationEventListener {
    private final ConversationService conversationService;

    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        try {
            TaskStatusChangedEvent.Message msg = event.message();
            conversationService.applyTaskFinished(msg.taskId(), msg.status(), msg.videoUrl(),
                    msg.outputType(), msg.errorMsg(), msg.costAmount());
        } catch (Exception e) {
            log.error("对话生成卡片回填失败: {}", e.getMessage(), e);
        }
    }
}
