package org.example.seedancegenarate.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.CanvasRunService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 画布节点终态回填：监听既有 {@link TaskStatusChangedEvent}（与 SSE 同源），按 taskId 反查
 * canvas_node，写状态与产物并推进下游 —— 后端是单一事实源，刷新不丢。
 * <p>
 * 同步执行于任务状态更新事务内，异常必须吞掉（不能因为画布回填失败而回滚任务更新）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasEventListener {

    private final CanvasRunService canvasRunService;

    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        try {
            TaskStatusChangedEvent.Message msg = event.message();
            canvasRunService.applyTaskFinished(msg.taskId(), msg.status(), msg.videoUrl(), msg.errorMsg());
        } catch (Exception e) {
            log.error("画布节点回填失败: {}", e.getMessage(), e);
        }
    }
}
