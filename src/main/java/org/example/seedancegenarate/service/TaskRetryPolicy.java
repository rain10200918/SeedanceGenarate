package org.example.seedancegenarate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seedancegenarate.engine.VideoEngine;
import org.example.seedancegenarate.engine.VideoEngineRegistry;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.service.Impl.VideoTaskServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「这个任务还该不该重投」的唯一出口。
 * <p>
 * 有三条路会问同一个问题——超龄兜底、作业丢失、超龄时节点不可达——以前只有第一条有答案，
 * 另两条各自写了不同的处置（一个没有、一个直接判失败）。三处分头判断必然漂移，
 * 所以收敛到这里：<b>能免费重投就重投，不能就判超时终止</b>。
 * <p>
 * 只对声明了 {@link VideoEngine#timeoutRetrySupported()} 的引擎重投——
 * 成功才扣费不等于提供方允许框架免费重提交（见 D-006）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRetryPolicy {

    private final VideoEngineRegistry videoEngineRegistry;
    private final AsyncJobService asyncJobService;
    private final TaskStatusTransitioner taskStatusTransitioner;
    private final ObjectMapper objectMapper;

    @Value("${video.timeout-retry-max:2}")
    private int timeoutRetryMax;

    @Value("${video.default-provider:seedance}")
    private String defaultProvider;

    /**
     * @param reason 重投耗尽时写给用户看的失败原因
     * @return true = 已入队重试；false = 已判失败
     */
    public boolean retryOrFail(VideoTask task, String reason) {
        if (task == null || task.getId() == null) {
            return false;
        }
        VideoEngine engine;
        try {
            engine = videoEngineRegistry.get(providerOf(task));
        } catch (Exception e) {
            taskStatusTransitioner.markTimedOutIfCurrent(task, reason);
            return false;
        }
        return retryOrFail(task, engine, reason);
    }

    /** 调用方手里已有 engine 时用这个重载，省一次注册表查找 */
    public boolean retryOrFail(VideoTask task, VideoEngine engine, String reason) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (engine != null && engine.timeoutRetrySupported() && retryCount < timeoutRetryMax) {
            log.info("入队自动重试: taskId={}, 第 {} 次, 原因={}",
                    task.businessTaskId(), retryCount + 1, reason);
            // 每一轮有独立幂等键；payload 固化期望 retry_count，外层 job 提交后宕机重领
            // 也不会再创建下一轮 attempt。
            asyncJobService.enqueue(VideoTaskServiceImpl.JOB_TYPE_TASK_RETRY,
                    "task:" + task.getId() + ":retry:" + retryCount + ":attempt:"
                            + (task.getCurrentAttemptId() == null ? "legacy" : task.getCurrentAttemptId()),
                    retryPayload(task, retryCount));
            return true;
        }
        String finalReason = engine != null && engine.timeoutRetrySupported()
                ? reason + "，已自动重试 " + retryCount + " 次仍未成功，已终止（可手动重试）"
                : reason + "，已终止（可手动重试）";
        taskStatusTransitioner.markTimedOutIfCurrent(task, finalReason);
        return false;
    }

    private String providerOf(VideoTask task) {
        return task.getProvider() == null || task.getProvider().isBlank()
                ? defaultProvider : task.getProvider().trim();
    }

    private String retryPayload(VideoTask task, int retryCount) {
        try {
            return objectMapper.writeValueAsString(new RetryPayload(task.getId(), retryCount,
                    task.getCurrentAttemptId(), task.getProviderTaskId(), task.getPhase(),
                    task.getProvider()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化任务重试作业", e);
        }
    }

    record RetryPayload(Long videoTaskId, Integer expectedRetryCount, Long expectedAttemptId,
                        String expectedProviderTaskId, String expectedPhase,
                        String expectedProvider) {
    }
}
