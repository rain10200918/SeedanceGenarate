package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.ApiKey;
import org.example.seedancegenarate.entity.VideoTask;

import java.util.List;

/**
 * 对外 API 的提交编排门面：幂等（Idempotency-Key）→ 模型定位/闸门 → 图片 URL 转存 →
 * 两阶段调用日志 → 共享提交（VideoSubmitService）。生成/计费逻辑零复制。
 */
public interface ApiVideoService {

    /** 提交上下文（controller 组装，含调用侧元数据） */
    record CreateContext(
            ApiKey apiKey,
            String requestId,
            String clientIp,
            String userAgent,
            String prompt,
            String model,
            List<String> imageUrls,
            Integer duration,
            String ratio,
            Double megapixels
    ) {
    }

    /**
     * 提交生成任务。幂等键已存在且已完成 → 直接返回原任务（不重复生成/扣费）。
     * 失败抛 {@link org.example.seedancegenarate.exception.ApiException}，并落 REJECTED 调用日志。
     */
    VideoTask create(CreateContext context);
}
