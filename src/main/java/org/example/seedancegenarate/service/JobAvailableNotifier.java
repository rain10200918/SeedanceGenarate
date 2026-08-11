package org.example.seedancegenarate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.config.AsyncJobProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 作业可用通知（事件驱动）：入队成功后发布 Redis 消息唤醒消费 Worker，
 * 避免「每 2 秒查一次作业表」的忙等。通知丢失不影响正确性——消费 Worker
 * 保留低频兜底扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobAvailableNotifier {
    private final StringRedisTemplate redisTemplate;
    private final AsyncJobProperties properties;

    public void notify(String jobType) {
        try {
            Long receivers = redisTemplate.convertAndSend(properties.getChannel(),
                    "{\"jobType\":\"" + jobType + "\"}");
            log.info("已发布作业可用通知: jobType={}, channel={}, receivers={}",
                    jobType, properties.getChannel(), receivers);
        } catch (Exception e) {
            // 通知只是加速；失败由兜底扫描接管，不影响任务正确性
            log.warn("作业通知发布失败（兜底扫描将接管）: jobType={}, channel={}, reason={}",
                    jobType, properties.getChannel(), e.getMessage());
        }
    }
}
