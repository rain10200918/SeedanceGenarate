package org.example.seedancegenarate.service;

import org.example.seedancegenarate.entity.VideoTask;

/** 任务预计完成时间（ETA）估算。 */
public interface TaskEtaService {

    /**
     * 估算任务当前阶段与剩余时间。
     * 前端按字段是否有值渲染，不感知提供方能力。
     */
    TaskEta estimate(VideoTask task);

    /** 任务完成（SUCCESS 落库）后调用：刷新该模型的平均耗时缓存。 */
    void refreshAvgDuration(String model);

    record TaskEta(
            String taskId,
            /** QUEUED 排队中 / RUNNING 生成中 / DONE 完成 / FAILED 失败 / UNKNOWN 无法预估 */
            String stage,
            /** 排队位置（0 = 下一个执行）；不可查为 null */
            Integer queuePosition,
            /** 预计剩余秒数；无法预估为 null */
            Long remainingSeconds,
            /** 已等待 / 已运行秒数 */
            Long elapsedSeconds,
            /** 0-100；无法预估为 null */
            Integer progressPercent
    ) {
    }
}
