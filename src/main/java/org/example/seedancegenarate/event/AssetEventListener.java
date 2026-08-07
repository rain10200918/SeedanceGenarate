package org.example.seedancegenarate.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.service.AssetService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 素材登记监听器：异步执行，失败仅记日志。
 * 素材登记是增值功能，绝不能阻塞任务提交响应，也不要求强一致——
 * 登记失败可容忍（同 URL 幂等，下次提交会自动补上）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetEventListener {

    private final AssetService assetService;

    @Async
    @EventListener
    public void onTaskSubmitted(TaskSubmittedEvent event) {
        if (!event.hasImages()) return;
        try {
            assetService.registerAssets(event.userId(), event.taskId(), event.imageUrls());
        } catch (Exception e) {
            log.error("素材登记失败 userId={} taskId={}", event.userId(), event.taskId(), e);
        }
    }
}
