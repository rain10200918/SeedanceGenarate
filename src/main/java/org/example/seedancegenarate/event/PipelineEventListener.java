package org.example.seedancegenarate.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.PipelineService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 流水线节点终态回填：监听现有 {@link TaskStatusChangedEvent}（SSE 同源事件），
 * 按 taskId 反查 pipeline_node 更新状态并汇总流水线状态——后端为单一事实源，刷新不丢。
 * 同步执行于任务状态更新事务内，异常必须吞掉（不能回滚任务更新）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineEventListener {

    private final PipelineService pipelineService;

    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        try {
            TaskStatusChangedEvent.Message msg = event.message();
            pipelineService.applyTaskFinished(msg.taskId(), msg.status(), msg.videoUrl(), msg.errorMsg());
        } catch (Exception e) {
            log.error("流水线节点回填失败: {}", e.getMessage(), e);
        }
    }
}
