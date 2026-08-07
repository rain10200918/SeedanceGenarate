package org.example.seedancegenarate.event;

import java.util.List;

/**
 * 任务提交成功事件。素材库等下游功能通过监听器解耦登记，
 * 核心提交链路（VideoSubmitService）不反向依赖任何增值功能。
 */
public record TaskSubmittedEvent(Long userId, String taskId, List<String> imageUrls) {

    public boolean hasImages() {
        return imageUrls != null && !imageUrls.isEmpty();
    }
}
